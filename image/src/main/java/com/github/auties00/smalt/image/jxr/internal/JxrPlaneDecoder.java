/* Ports jxrlib 1.1 (image/decode/strdec.c, segdec.c, strPredQuantDec.c, decode.c and image/sys/strcodec.c,
   image.c, strPredQuant.c, adapthuff.c: the decoding of the image planes). Copyright (c) Microsoft Corporation; BSD
   2-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxr.internal;

import com.github.auties00.smalt.MediaException;

/**
 * Decodes the image planes of a JPEG XR codestream into their reconstructed samples: the entropy decoding of the
 * DC, lowpass and highpass bands, the inverse prediction and quantization, and the inverse transform with its
 * overlap filtering, one macroblock row at a time.
 *
 * <p>An instance decodes one plane; the alpha plane that follows the primary one in the same codestream has a
 * secondary instance sharing its bit-streams.
 */
final class JxrPlaneDecoder {
    /** The offset of each 4x4 block within a macroblock's coefficients. */
    static final int[] BLOCK_OFFSET = {0, 64, 16, 80, 128, 192, 144, 208, 32, 96, 48, 112, 160, 224, 176, 240};

    /** The offset of each 4x4 block within a 4:2:0 chroma macroblock. */
    static final int[] BLOCK_OFFSET_UV = {0, 32, 16, 48};

    /** The offset of each 4x4 block within a 4:2:2 chroma macroblock. */
    static final int[] BLOCK_OFFSET_UV_422 = {0, 64, 16, 80, 32, 96, 48, 112};

    /** The coefficient order of a highpass block, and of the lowpass coefficients of a macroblock. */
    static final int[][] DCT_INDEX = {
            {0, 5, 1, 6, 10, 12, 8, 14, 2, 4, 3, 7, 9, 13, 11, 15},
            {0, 5, 1, 6, 10, 12, 8, 14, 2, 4, 3, 7, 9, 13, 11, 15},
            {0, 128, 64, 208, 32, 240, 48, 224, 16, 192, 80, 144, 112, 176, 96, 160}};

    /** The position of each pixel of a 16x16 macroblock in its reconstructed samples, by row then column. */
    static final int[][] IDX_CC = {
            {0x00, 0x01, 0x05, 0x04, 0x40, 0x41, 0x45, 0x44, 0x80, 0x81, 0x85, 0x84, 0xc0, 0xc1, 0xc5, 0xc4},
            {0x02, 0x03, 0x07, 0x06, 0x42, 0x43, 0x47, 0x46, 0x82, 0x83, 0x87, 0x86, 0xc2, 0xc3, 0xc7, 0xc6},
            {0x0a, 0x0b, 0x0f, 0x0e, 0x4a, 0x4b, 0x4f, 0x4e, 0x8a, 0x8b, 0x8f, 0x8e, 0xca, 0xcb, 0xcf, 0xce},
            {0x08, 0x09, 0x0d, 0x0c, 0x48, 0x49, 0x4d, 0x4c, 0x88, 0x89, 0x8d, 0x8c, 0xc8, 0xc9, 0xcd, 0xcc},
            {0x10, 0x11, 0x15, 0x14, 0x50, 0x51, 0x55, 0x54, 0x90, 0x91, 0x95, 0x94, 0xd0, 0xd1, 0xd5, 0xd4},
            {0x12, 0x13, 0x17, 0x16, 0x52, 0x53, 0x57, 0x56, 0x92, 0x93, 0x97, 0x96, 0xd2, 0xd3, 0xd7, 0xd6},
            {0x1a, 0x1b, 0x1f, 0x1e, 0x5a, 0x5b, 0x5f, 0x5e, 0x9a, 0x9b, 0x9f, 0x9e, 0xda, 0xdb, 0xdf, 0xde},
            {0x18, 0x19, 0x1d, 0x1c, 0x58, 0x59, 0x5d, 0x5c, 0x98, 0x99, 0x9d, 0x9c, 0xd8, 0xd9, 0xdd, 0xdc},
            {0x20, 0x21, 0x25, 0x24, 0x60, 0x61, 0x65, 0x64, 0xa0, 0xa1, 0xa5, 0xa4, 0xe0, 0xe1, 0xe5, 0xe4},
            {0x22, 0x23, 0x27, 0x26, 0x62, 0x63, 0x67, 0x66, 0xa2, 0xa3, 0xa7, 0xa6, 0xe2, 0xe3, 0xe7, 0xe6},
            {0x2a, 0x2b, 0x2f, 0x2e, 0x6a, 0x6b, 0x6f, 0x6e, 0xaa, 0xab, 0xaf, 0xae, 0xea, 0xeb, 0xef, 0xee},
            {0x28, 0x29, 0x2d, 0x2c, 0x68, 0x69, 0x6d, 0x6c, 0xa8, 0xa9, 0xad, 0xac, 0xe8, 0xe9, 0xed, 0xec},
            {0x30, 0x31, 0x35, 0x34, 0x70, 0x71, 0x75, 0x74, 0xb0, 0xb1, 0xb5, 0xb4, 0xf0, 0xf1, 0xf5, 0xf4},
            {0x32, 0x33, 0x37, 0x36, 0x72, 0x73, 0x77, 0x76, 0xb2, 0xb3, 0xb7, 0xb6, 0xf2, 0xf3, 0xf7, 0xf6},
            {0x3a, 0x3b, 0x3f, 0x3e, 0x7a, 0x7b, 0x7f, 0x7e, 0xba, 0xbb, 0xbf, 0xbe, 0xfa, 0xfb, 0xff, 0xfe},
            {0x38, 0x39, 0x3d, 0x3c, 0x78, 0x79, 0x7d, 0x7c, 0xb8, 0xb9, 0xbd, 0xbc, 0xf8, 0xf9, 0xfd, 0xfc}};

    /** The position of each pixel of an 8x8 4:2:0 chroma macroblock in its reconstructed samples. */
    static final int[][] IDX_CC_420 = {
            {0x00, 0x01, 0x05, 0x04, 0x20, 0x21, 0x25, 0x24},
            {0x02, 0x03, 0x07, 0x06, 0x22, 0x23, 0x27, 0x26},
            {0x0a, 0x0b, 0x0f, 0x0e, 0x2a, 0x2b, 0x2f, 0x2e},
            {0x08, 0x09, 0x0d, 0x0c, 0x28, 0x29, 0x2d, 0x2c},
            {0x10, 0x11, 0x15, 0x14, 0x30, 0x31, 0x35, 0x34},
            {0x12, 0x13, 0x17, 0x16, 0x32, 0x33, 0x37, 0x36},
            {0x1a, 0x1b, 0x1f, 0x1e, 0x3a, 0x3b, 0x3f, 0x3e},
            {0x18, 0x19, 0x1d, 0x1c, 0x38, 0x39, 0x3d, 0x3c}};

    /** The number of 4x4 chroma blocks of a macroblock per internal color format. */
    private static final int[] CHROMA_BLOCKS = {0, 4, 8, 16, 16, 16, 16, 0, 0};

    /** The lowpass zigzag scan. */
    private static final int[] ZIGZAG_LOWPASS = {0, 1, 4, 5, 2, 8, 6, 9, 3, 12, 10, 7, 13, 11, 14, 15};

    /** The horizontal highpass zigzag scan. */
    private static final int[] ZIGZAG_HORIZONTAL = {0, 1, 4, 5, 2, 8, 6, 9, 3, 12, 10, 7, 13, 11, 14, 15};

    /** The vertical highpass zigzag scan. */
    private static final int[] ZIGZAG_VERTICAL = {0, 4, 8, 5, 1, 12, 9, 6, 2, 13, 3, 15, 7, 10, 14, 11};

    /** The bin of the significant run code per largest run. */
    private static final int[] RUN_BIN = {-1, -1, -1, -1, 2, 2, 2, 1, 1, 1, 1, 0, 0, 0, 0};

    /** The fixed-length bits of each significant run code. */
    private static final int[] RUN_FIXED_LENGTH = {0, 0, 1, 1, 3, 0, 0, 1, 1, 2, 0, 0, 0, 0, 1};

    /** The base of each significant run code. */
    private static final int[] RUN_REMAP = {1, 2, 3, 5, 7, 1, 2, 3, 5, 7, 1, 2, 3, 4, 5};

    /** The alphabet sizes of the 21 adaptive codes of a coding context. */
    private static final int[] ALPHABETS = {5, 4, 8, 7, 7, 12, 6, 6, 12, 6, 6, 7, 7, 12, 6, 6, 12, 6, 6, 7, 7};

    /** The offset of the DC codes. */
    private static final int CTDC = 5;

    /** The number of codes of a band. */
    private static final int CONTEXTX = 8;

    /** The initial adaptive scan total of the first coefficient. */
    private static final int MAXTOTAL = 32767;

    /** The reciprocal mantissas and exponents of the quantizer steps. */
    private static final int[][] QP_RECIPROCAL = {
            {0x0, 0}, {0x0, 0}, {0x0, 1}, {0xaaaaaaab, 1}, {0x0, 2}, {0xcccccccd, 2}, {0xaaaaaaab, 2},
            {0x92492493, 2}, {0x0, 3}, {0xe38e38e4, 3}, {0xcccccccd, 3}, {0xba2e8ba3, 3}, {0xaaaaaaab, 3},
            {0x9d89d89e, 3}, {0x92492493, 3}, {0x88888889, 3}, {0x0, 4}, {0xf0f0f0f1, 4}, {0xe38e38e4, 4},
            {0xd79435e6, 4}, {0xcccccccd, 4}, {0xc30c30c4, 4}, {0xba2e8ba3, 4}, {0xb21642c9, 4}, {0xaaaaaaab, 4},
            {0xa3d70a3e, 4}, {0x9d89d89e, 4}, {0x97b425ee, 4}, {0x92492493, 4}, {0x8d3dcb09, 4}, {0x88888889, 4},
            {0x84210843, 4}};

    /** The shift of the quantizer steps of the scaled arithmetic. */
    private static final int SHIFTZERO = 1;

    /** The fractional bits of the quantizer steps. */
    private static final int QPFRACBITS = 2;

    /** The weight of the first channel's mean in a model update, per band. */
    private static final int[] MODEL_WEIGHT = {240, 12, 1};

    /** The weight of the other channels' mean in a model update, per band and channel count. */
    private static final int[][] MODEL_WEIGHT_CHANNELS = {
            {0, 240, 120, 80, 60, 48, 40, 34, 30, 27, 24, 22, 20, 18, 17, 16},
            {0, 12, 6, 4, 3, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1},
            {0, 16, 8, 5, 4, 3, 3, 2, 2, 2, 2, 1, 1, 1, 1, 1}};

    /** The weight of the chroma mean in a model update, per band, for 4:2:0 then 4:2:2. */
    private static final int[] MODEL_WEIGHT_CHROMA = {120, 37, 2, 120, 18, 1};

    /** The weight that biases the prediction direction. */
    private static final int ORIENT_WEIGHT = 4;

    /**
     * The image header.
     */
    final JxrHeader header;

    /**
     * The header of this plane.
     */
    final JxrHeader.Plane plane;

    /** The internal color format. */
    final int colorFormat;

    /** The number of channels. */
    final int channels;

    /** The macroblock columns. */
    final int mbWidth;

    /** The macroblock rows. */
    final int mbHeight;

    /** Whether this plane is the alpha plane following the primary one. */
    final boolean secondary;

    /** The alpha plane of a primary plane, or {@code null}. */
    JxrPlaneDecoder next;

    /** The decode switches, shared by a primary plane and its alpha plane. */
    final Switches switches;

    /** The two macroblock rows of each channel, the previous one and the current one. */
    final int[][] rows;

    /** The start of the previous macroblock row of each channel in {@link #rows}. */
    final int[] previous;

    /** The start of the current macroblock row of each channel in {@link #rows}. */
    final int[] current;

    /** The offset of the current macroblock from the start of its row, per channel. */
    final int[] position;

    /** The coefficients of a macroblock of each channel. */
    final int[] stride;

    /**
     * The current macroblock row, up to one past the last.
     */
    int row;

    /**
     * The current macroblock column, up to one past the last.
     */
    int column;

    /**
     * The tile row of the current macroblock.
     */
    int tileRow;

    /**
     * The tile column of the current macroblock.
     */
    int tileColumn;

    /**
     * Whether the current macroblock starts its tile horizontally.
     */
    boolean contextLeft;

    /**
     * Whether the current macroblock starts its tile vertically.
     */
    boolean contextTop;

    /**
     * Whether the adaptive codes adapt after the current macroblock.
     */
    boolean resetContext;

    /**
     * Whether the adaptive scan totals reset before the current macroblock.
     */
    boolean resetTotals;

    /** The quantized DC and lowpass coefficients of the current macroblock, per channel. */
    final int[][] blockDc = new int[16][16];

    /** The highpass scan orientation of the current macroblock. */
    int orientation;

    /** The coded block patterns of the current macroblock. */
    final int[] cbp = new int[16];

    /** The coded block pattern differences of the current macroblock. */
    final int[] diffCbp = new int[16];

    /**
     * The lowpass quantizer index of the current macroblock.
     */
    int qIndexLp;

    /**
     * The highpass quantizer index of the current macroblock.
     */
    int qIndexHp;

    /** The prediction information of the current macroblock row, per channel. */
    PredInfo[][] pred;

    /** The prediction information of the previous macroblock row, per channel. */
    PredInfo[][] predPrevious;

    /** The quantizers of each tile column. */
    final Tile[] tiles;

    /** The coding context of each tile column. */
    final Context[] contexts;

    /** The bit-stream of the headers and, without an index table, of the macroblocks. */
    JxrBits headerStream;

    /** The bit-streams of the packets, per tile column and band. */
    JxrBits[] streams;

    /** The number of bands stored. */
    int bands;

    /** The offsets of the packets, relative to {@link #headerSize}. */
    long[] indexTable;

    /** The index in the data of the first packet. */
    long headerSize;

    /** The codestream. */
    final byte[] data;

    /** The index one past the codestream. */
    final int end;

    /**
     * The macroblock column the last inverse transform ran at.
     */
    int mbX;

    /**
     * The macroblock row the last inverse transform ran at.
     */
    int mbY;

    /**
     * The tile row index of the hard tile boundary tracking.
     */
    int hardTileX;

    /**
     * The tile column index of the hard tile boundary tracking.
     */
    int hardTileY;

    /**
     * Whether a hard vertical tile boundary is at the current column.
     */
    boolean verticalTileBoundary;

    /**
     * Whether a hard horizontal tile boundary is at the current row.
     */
    boolean horizontalTileBoundary;

    /**
     * Whether the current column is the one left of a hard boundary.
     */
    boolean oneLeftOfVerticalBoundary;

    /**
     * Whether the current column is the one right of a hard boundary.
     */
    boolean oneRightOfVerticalBoundary;

    /**
     * The chroma corner samples saved before the second-stage overlap.
     */
    final int[][] predBefore = new int[2][2];

    /**
     * The chroma corner samples saved after the second-stage overlap.
     */
    final int[][] predAfter = new int[2][2];

    /** The reconstructed samples of each channel, row-major over the macroblock-aligned frame. */
    final int[][] planes;

    /** The width of each channel's plane. */
    final int[] planeWidth;

    /**
     * Holds the decode switches of the planes of an image.
     */
    static final class Switches {
        /**
         * Whether the highpass band is decoded.
         */
        boolean decodeHp;

        /**
         * Whether the lowpass band is decoded.
         */
        boolean decodeLp;

        /**
         * Whether the flexbits are skipped.
         */
        boolean skipFlexbits;
    }

    /**
     * Holds the prediction information of a macroblock.
     */
    static final class PredInfo {
        /**
         * The lowpass quantizer index.
         */
        int qpIndex;

        /**
         * The coded block pattern.
         */
        int cbp;

        /**
         * The quantized DC coefficient.
         */
        int dc;

        /**
         * The first row and column of the lowpass coefficients.
         */
        final int[] ad = new int[6];
    }

    /**
     * Holds a quantizer.
     */
    static final class Quantizer {
        /**
         * The quantizer index.
         */
        int index;

        /**
         * The quantizer step.
         */
        int qp;

        /**
         * The rounding offset of the step.
         */
        int offset;

        /**
         * The mantissa of the reciprocal of the step.
         */
        int mantissa;

        /**
         * The exponent of the reciprocal of the step.
         */
        int exponent;

        /**
         * Copies another quantizer into this one.
         *
         * @param other the quantizer copied
         */
        void copyFrom(Quantizer other) {
            index = other.index;
            qp = other.qp;
            offset = other.offset;
            mantissa = other.mantissa;
            exponent = other.exponent;
        }
    }

    /**
     * Holds the quantizers of a tile column.
     */
    static final class Tile {
        /**
         * The DC quantizers of each channel.
         */
        final Quantizer[][] dc = new Quantizer[16][];

        /**
         * The lowpass quantizers of each channel.
         */
        final Quantizer[][] lp = new Quantizer[16][];

        /**
         * The highpass quantizers of each channel.
         */
        final Quantizer[][] hp = new Quantizer[16][];

        /**
         * The number of lowpass quantizers.
         */
        int numQpLp = 1;

        /**
         * The number of highpass quantizers.
         */
        int numQpHp = 1;

        /**
         * The bits of a macroblock lowpass quantizer index.
         */
        int bitsLp;

        /**
         * The bits of a macroblock highpass quantizer index.
         */
        int bitsHp;

        /**
         * Whether the lowpass band takes the DC quantizer.
         */
        boolean useDc;

        /**
         * Whether the highpass band takes the lowpass quantizers.
         */
        boolean useLp;
    }

    /**
     * Holds an adaptive scan order.
     */
    static final class Scan {
        /**
         * The count of each scan position.
         */
        final int[] total = new int[16];

        /**
         * The coefficient of each scan position.
         */
        final int[] scan = new int[16];

        /**
         * Swaps a scan position with the one before it.
         *
         * @param index the later position
         */
        void swap(int index) {
            var t = total[index];
            total[index] = total[index - 1];
            total[index - 1] = t;
            var s = scan[index];
            scan[index] = scan[index - 1];
            scan[index - 1] = s;
        }
    }

    /**
     * Holds an adaptive fixed-length model.
     */
    static final class Model {
        /**
         * The adaptation state of the first and the other channels.
         */
        final int[] state = new int[2];

        /**
         * The fixed-length bits of the first and the other channels.
         */
        final int[] bits = new int[2];

        /**
         * The band: 1 DC, 2 lowpass, 3 highpass.
         */
        final int band;

        /**
         * Creates a model of a band.
         *
         * @param band the band: 1 DC, 2 lowpass, 3 highpass
         */
        Model(int band) {
            this.band = band;
        }
    }

    /**
     * Holds the coding context of a tile column.
     */
    static final class Context {
        /**
         * The bit-stream of the DC band.
         */
        JxrBits dcStream;

        /**
         * The bit-stream of the lowpass band.
         */
        JxrBits lpStream;

        /**
         * The bit-stream of the highpass band.
         */
        JxrBits acStream;

        /**
         * The bit-stream of the flexbits.
         */
        JxrBits flStream;

        /**
         * The code of the block coded block patterns.
         */
        final JxrHuffman cbpY;

        /**
         * The code of the macroblock coded block patterns.
         */
        final JxrHuffman cbpY1;

        /**
         * The codes of the runs, indices and levels.
         */
        final JxrHuffman[] codes = new JxrHuffman[21];

        /**
         * The lowpass scan.
         */
        final Scan lowpass = new Scan();

        /**
         * The horizontal highpass scan.
         */
        final Scan horizontal = new Scan();

        /**
         * The vertical highpass scan.
         */
        final Scan vertical = new Scan();

        /**
         * The fixed-length model of the highpass band.
         */
        final Model ac = new Model(3);

        /**
         * The fixed-length model of the lowpass band.
         */
        final Model lp = new Model(2);

        /**
         * The fixed-length model of the DC band.
         */
        final Model dc = new Model(1);

        /**
         * The count of lowpass patterns without coefficients.
         */
        int cbpCountZero;

        /**
         * The count of lowpass patterns of every channel.
         */
        int cbpCountMax;

        /**
         * The coded block counts of the CBP model.
         */
        final int[] count0 = new int[2];

        /**
         * The uncoded block counts of the CBP model.
         */
        final int[] count1 = new int[2];

        /**
         * The prediction state of the CBP model.
         */
        final int[] cbpState = new int[2];

        /**
         * The trimmed flexbits of the tile.
         */
        int trimFlexbits;

        /**
         * Creates a context.
         *
         * @param cbpSize the alphabet size of the block pattern code
         */
        Context(int cbpSize) {
            cbpY = new JxrHuffman(cbpSize);
            cbpY1 = new JxrHuffman(5);
            for (var index = 0; index < codes.length; index++) {
                codes[index] = new JxrHuffman(ALPHABETS[index]);
            }
        }
    }

    /**
     * Prepares the decode of a plane.
     *
     * @param data the codestream bytes
     * @param end the index one past the codestream
     * @param header the image header
     * @param plane the plane header
     * @param secondary whether the plane is the alpha plane following the primary one
     * @param switches the decode switches
     * @throws MediaException.Malformed if the headers are inconsistent
     */
    JxrPlaneDecoder(byte[] data, int end, JxrHeader header, JxrHeader.Plane plane, boolean secondary,
                    Switches switches) {
        this.data = data;
        this.end = end;
        this.header = header;
        this.plane = plane;
        this.secondary = secondary;
        this.switches = switches;
        this.colorFormat = secondary ? JxrHeader.Y_ONLY : plane.colorFormat;
        this.channels = secondary ? 1 : plane.channels;
        var fullWidth = (long) header.width + header.extraLeft + header.extraRight;
        var fullHeight = (long) header.height + header.extraTop + header.extraBottom;
        mbWidth = (int) ((fullWidth + 15) / 16);
        mbHeight = (int) ((fullHeight + 15) / 16);
        var chroma = CHROMA_BLOCKS[colorFormat] * 16;
        rows = new int[channels][];
        previous = new int[channels];
        current = new int[channels];
        position = new int[channels];
        stride = new int[channels];
        planes = new int[channels][];
        planeWidth = new int[channels];
        for (var c = 0; c < channels; c++) {
            stride[c] = c == 0 ? 256 : chroma;
            var size = Math.multiplyExact(stride[c], mbWidth);
            rows[c] = new int[Math.multiplyExact(size, 2)];
            previous[c] = 0;
            current[c] = size;
            var subsampledX = c > 0 && (colorFormat == JxrHeader.YUV_420 || colorFormat == JxrHeader.YUV_422);
            var subsampledY = c > 0 && colorFormat == JxrHeader.YUV_420;
            planeWidth[c] = mbWidth * (subsampledX ? 8 : 16);
            planes[c] = new int[Math.multiplyExact(planeWidth[c], mbHeight * (subsampledY ? 8 : 16))];
        }
        pred = new PredInfo[channels][mbWidth];
        predPrevious = new PredInfo[channels][mbWidth];
        for (var c = 0; c < channels; c++) {
            for (var x = 0; x < mbWidth; x++) {
                pred[c][x] = new PredInfo();
                predPrevious[c][x] = new PredInfo();
            }
        }
        tiles = new Tile[header.tileColumnsMinus1 + 1];
        contexts = new Context[header.tileColumnsMinus1 + 1];
        var cbpSize = colorFormat == JxrHeader.Y_ONLY || colorFormat == JxrHeader.NCOMPONENT
                || colorFormat == JxrHeader.CMYK ? 5 : 9;
        for (var t = 0; t < tiles.length; t++) {
            tiles[t] = new Tile();
            contexts[t] = new Context(cbpSize);
            resetContext(contexts[t]);
        }
        prepareQuantizers();
    }

    /**
     * Allocates fresh quantizers of every channel.
     */
    private Quantizer[][] allocate(Quantizer[][] target, int count) {
        for (var c = 0; c < channels; c++) {
            target[c] = new Quantizer[count];
            for (var index = 0; index < count; index++) {
                target[c][index] = new Quantizer();
            }
        }
        return target;
    }

    /**
     * Sets up the uniform frame quantizers (StrDecInit).
     */
    private void prepareQuantizers() {
        var mode = plane.qpMode;
        var tile0 = tiles[0];
        if ((mode & 1) == 0) {
            allocate(tile0.dc, 1);
            for (var t = 1; t < tiles.length; t++) {
                System.arraycopy(tile0.dc, 0, tiles[t].dc, 0, channels);
            }
            for (var c = 0; c < channels; c++) {
                tile0.dc[c][0].index = plane.qpDc[c];
            }
            format(tile0.dc, (mode >> 3) & 3, 0, true);
        }
        if (plane.subband != JxrHeader.SB_DC_ONLY) {
            if ((mode & 2) == 0) {
                allocate(tile0.lp, 1);
                for (var t = 1; t < tiles.length; t++) {
                    System.arraycopy(tile0.lp, 0, tiles[t].lp, 0, channels);
                }
                if ((mode & 0x200) == 0) {
                    useDcQuantizer(0);
                } else {
                    for (var c = 0; c < channels; c++) {
                        tile0.lp[c][0].index = plane.qpLp[c];
                    }
                    format(tile0.lp, (mode >> 5) & 3, 0, true);
                }
            }
            if (plane.subband != JxrHeader.SB_NO_HIGHPASS && (mode & 4) == 0) {
                allocate(tile0.hp, 1);
                for (var t = 1; t < tiles.length; t++) {
                    System.arraycopy(tile0.hp, 0, tiles[t].hp, 0, channels);
                }
                if ((mode & 0x400) == 0) {
                    useLpQuantizer(1, 0);
                } else {
                    for (var c = 0; c < channels; c++) {
                        tile0.hp[c][0].index = plane.qpHp[c];
                    }
                    format(tile0.hp, (mode >> 7) & 3, 0, false);
                }
            }
        }
    }

    /**
     * Copies the DC quantizer of each channel of a tile into its first lowpass quantizer.
     */
    private void useDcQuantizer(int tile) {
        for (var c = 0; c < channels; c++) {
            tiles[tile].lp[c][0].copyFrom(tiles[tile].dc[c][0]);
        }
    }

    /**
     * Copies the lowpass quantizers of each channel of a tile into its highpass quantizers.
     */
    private void useLpQuantizer(int count, int tile) {
        for (var c = 0; c < channels; c++) {
            for (var index = 0; index < count; index++) {
                tiles[tile].hp[c][index].copyFrom(tiles[tile].lp[c][index]);
            }
        }
    }

    /**
     * Completes the quantizers of one position from their indices: copies the first or second channel's into the
     * others by the channel mode and derives the steps.
     */
    private void format(Quantizer[][] quantizers, int mode, int index, boolean shiftedUv) {
        for (var c = 0; c < channels; c++) {
            if (c > 0) {
                if (mode == 0) {
                    quantizers[c][index].copyFrom(quantizers[0][index]);
                } else if (mode == 1) {
                    quantizers[c][index].copyFrom(quantizers[1][index]);
                }
            }
            remap(quantizers[c][index], c > 0 && shiftedUv ? SHIFTZERO - 1 : SHIFTZERO, plane.scaled);
        }
    }

    /**
     * Derives the quantizer step of an index (remapQP).
     */
    static void remap(Quantizer quantizer, int shift, boolean scaled) {
        var index = quantizer.index;
        if (index == 0) {
            quantizer.qp = 1;
            quantizer.mantissa = 0;
            quantizer.exponent = 0;
            quantizer.offset = 0;
            return;
        }
        int man;
        int exp;
        if (!scaled) {
            var base = -QPFRACBITS;
            if (index < 32) {
                man = (index + 3) >> 2;
                exp = base + 2;
            } else if (index < 48) {
                man = (16 + (index & 0xf) + 1) >> 1;
                exp = ((index >> 4) - 1) + 1 + base;
            } else {
                man = 16 + (index & 0xf);
                exp = ((index >> 4) - 1) + base;
            }
        } else if (index < 16) {
            man = index;
            exp = shift;
        } else {
            man = 16 + (index & 0xf);
            exp = ((index >> 4) - 1) + shift;
        }
        quantizer.qp = man << exp;
        quantizer.mantissa = QP_RECIPROCAL[man][0];
        quantizer.exponent = QP_RECIPROCAL[man][1] + exp;
        quantizer.offset = (quantizer.qp * 3 + 1) >> 3;
    }

    /**
     * Resets a coding context (ResetCodingContextDec).
     */
    private static void resetContext(Context context) {
        context.cbpY.reset();
        context.cbpY1.reset();
        for (var code : context.codes) {
            code.reset();
        }
        adaptLowpass(context);
        adaptHighpass(context);
        for (var i = 0; i < 16; i++) {
            context.lowpass.scan[i] = ZIGZAG_LOWPASS[i];
            context.horizontal.scan[i] = DCT_INDEX[0][ZIGZAG_HORIZONTAL[i]];
            context.vertical.scan[i] = DCT_INDEX[0][ZIGZAG_VERTICAL[i]];
        }
        resetModel(context.ac, 0);
        resetModel(context.lp, 4);
        resetModel(context.dc, 8);
        context.cbpCountMax = context.cbpCountZero = 1;
        context.count0[0] = context.count0[1] = -4;
        context.count1[0] = context.count1[1] = 4;
        context.cbpState[0] = context.cbpState[1] = 0;
    }

    /**
     * Resets a fixed-length model to its initial bits.
     */
    private static void resetModel(Model model, int bits) {
        model.state[0] = model.state[1] = 0;
        model.bits[0] = model.bits[1] = bits;
    }

    /**
     * Adapts the DC and lowpass codes (AdaptLowpassDec).
     */
    private static void adaptLowpass(Context context) {
        for (var k = 0; k < CONTEXTX + CTDC; k++) {
            context.codes[k].adapt();
        }
    }

    /**
     * Adapts the highpass codes (AdaptHighpassDec).
     */
    private static void adaptHighpass(Context context) {
        context.cbpY.adapt();
        context.cbpY1.adapt();
        for (var k = 0; k < CONTEXTX; k++) {
            context.codes[k + CONTEXTX + CTDC].adapt();
        }
    }

    /**
     * Updates an adaptive fixed-length model at the end of a macroblock (UpdateModelMB).
     */
    private static void updateModel(int cf, int channels, int[] mean, Model model) {
        var band = model.band - 1;
        mean[0] *= MODEL_WEIGHT[band];
        if (cf == JxrHeader.YUV_420) {
            mean[1] *= MODEL_WEIGHT_CHROMA[band];
        } else if (cf == JxrHeader.YUV_422) {
            mean[1] *= MODEL_WEIGHT_CHROMA[3 + band];
        } else {
            mean[1] *= MODEL_WEIGHT_CHANNELS[band][channels - 1];
            if (model.band == 3) {
                mean[1] >>= 4;
            }
        }
        for (var j = 0; j < 2; j++) {
            var ms = model.state[j];
            var delta = (mean[j] - 70) >> 2;
            if (delta <= -8) {
                delta += 4;
                if (delta < -16) {
                    delta = -16;
                }
                ms += delta;
                if (ms < -8) {
                    if (model.bits[j] == 0) {
                        ms = -8;
                    } else {
                        ms = 0;
                        model.bits[j]--;
                    }
                }
            } else if (delta >= 8) {
                delta -= 4;
                if (delta > 15) {
                    delta = 15;
                }
                ms += delta;
                if (ms > 8) {
                    if (model.bits[j] >= 15) {
                        model.bits[j] = 15;
                        ms = 8;
                    } else {
                        ms = 0;
                        model.bits[j]++;
                    }
                }
            }
            model.state[j] = ms;
            if (cf == JxrHeader.Y_ONLY) {
                break;
            }
        }
    }

    /**
     * Reads the index table and attaches the bit-streams of the packets (StrIODecInit, readIndexTable,
     * setBitIOPointers).
     *
     * @param start the index of the first byte after the plane headers
     * @throws MediaException.Malformed if the index table is invalid
     */
    void attachStreams(int start) {
        var subband = plane.subband;
        bands = subband == JxrHeader.SB_DC_ONLY ? 1 : subband == JxrHeader.SB_NO_HIGHPASS ? 2
                : subband == JxrHeader.SB_NO_FLEXBITS ? 3 : 4;
        var columns = header.tileColumnsMinus1 + 1;
        int count;
        if (!header.indexTable) {
            count = 0;
        } else if (!header.frequency) {
            count = columns;
        } else {
            count = columns * bands;
        }
        headerStream = new JxrBits(data, start, end);
        streams = new JxrBits[count];
        for (var index = 0; index < count; index++) {
            streams[index] = new JxrBits(data, end, end);
        }
        if (count > 0) {
            indexTable = new long[Math.multiplyExact(count, header.tileRowsMinus1 + 1)];
            if (headerStream.read(16) != 1) {
                throw new MediaException.Malformed("the JPEG XR index table has no start code");
            }
            for (var index = 0; index < indexTable.length; index++) {
                indexTable[index] = variableLengthWord(headerStream);
            }
        }
        headerSize = variableLengthWord(headerStream);
        headerStream.alignToByte();
        headerSize += headerStream.bytePosition();
        if (headerStream.overrun()) {
            throw new MediaException.Malformed("truncated JPEG XR index table");
        }
        pointContexts();
    }

    /**
     * Shares the bit-streams of the primary plane with its alpha plane.
     *
     * @param primary the primary plane
     */
    void shareStreams(JxrPlaneDecoder primary) {
        headerStream = primary.headerStream;
        streams = primary.streams;
        bands = primary.bands;
        indexTable = primary.indexTable;
        headerSize = primary.headerSize;
        pointContexts();
    }

    /**
     * Points the coding context of each tile column at its bit-streams.
     */
    private void pointContexts() {
        if (streams.length == 0) {
            var context = contexts[0];
            context.dcStream = context.lpStream = context.acStream = context.flStream = headerStream;
            return;
        }
        for (var k = 0; k < contexts.length; k++) {
            var context = contexts[k];
            if (!header.frequency) {
                context.dcStream = context.lpStream = context.acStream = context.flStream = streams[k];
            } else {
                context.dcStream = streams[k * bands];
                context.lpStream = bands > 1 ? streams[k * bands + 1] : null;
                context.acStream = bands > 2 ? streams[k * bands + 2] : null;
                context.flStream = bands > 3 ? streams[k * bands + 3] : null;
            }
        }
    }

    /**
     * Reads a variable-length size of the index table, 0 for an escape (GetVLWordEsc).
     */
    private static long variableLengthWord(JxrBits bits) {
        long value = bits.read(8);
        if (value == 0xfd || value == 0xfe || value == 0xff) {
            return 0;
        }
        if (value < 0xfb) {
            return value << 8 | bits.read(8);
        }
        value -= 0xfb;
        if (value != 0) {
            value = (long) bits.read(16) << 16;
            value = (value | bits.read(16)) << 16;
            value <<= 16;
        }
        value |= (long) bits.read(16) << 16;
        value |= bits.read(16);
        return value;
    }

    /**
     * Decodes the plane, with its alpha plane when it has one, into {@link #planes} (ImageStrDecDecode).
     *
     * @throws MediaException.Malformed if the bit-stream is invalid
     */
    void decode() {
        for (row = 0; row <= mbHeight; row++) {
            for (var plane = this; plane != null; plane = plane == this ? next : null) {
                for (var c = 0; c < plane.channels; c++) {
                    java.util.Arrays.fill(plane.rows[c], plane.current[c], plane.current[c] + plane.stride[c]
                            * plane.mbWidth, 0);
                }
            }
            for (column = 0; column <= mbWidth; column++) {
                processMacroblock();
            }
            if (row > 0) {
                load();
                if (next != null) {
                    next.load();
                }
            }
            for (var plane = this; plane != null; plane = plane == this ? next : null) {
                var swap = plane.pred;
                plane.pred = plane.predPrevious;
                plane.predPrevious = swap;
                for (var c = 0; c < plane.channels; c++) {
                    var t = plane.previous[c];
                    plane.previous[c] = plane.current[c];
                    plane.current[c] = t;
                }
            }
        }
    }

    /**
     * Copies the finished previous macroblock row of every channel into the planes.
     */
    private void load() {
        var mbRow = row - 1;
        for (var c = 0; c < channels; c++) {
            var buffer = rows[c];
            var base = previous[c];
            var target = planes[c];
            var width = planeWidth[c];
            if (stride[c] == 64) {
                for (var y = 0; y < 8; y++) {
                    var line = (mbRow * 8 + y) * width;
                    for (var x = 0; x < width; x++) {
                        target[line + x] = buffer[base + (x >> 3) * 64 + IDX_CC_420[y][x & 7]];
                    }
                }
            } else if (stride[c] == 128) {
                for (var y = 0; y < 16; y++) {
                    var line = (mbRow * 16 + y) * width;
                    for (var x = 0; x < width; x++) {
                        target[line + x] = buffer[base + (x >> 3) * 128 + IDX_CC[y][x & 7]];
                    }
                }
            } else {
                for (var y = 0; y < 16; y++) {
                    var line = (mbRow * 16 + y) * width;
                    for (var x = 0; x < width; x++) {
                        target[line + x] = buffer[base + (x >> 4) * 256 + IDX_CC[y][x & 15]];
                    }
                }
            }
        }
    }

    /**
     * Decodes and inverse transforms the macroblock at the current position of the plane and of its alpha plane
     * (processMacroblockDec).
     */
    private void processMacroblock() {
        var plane = this;
        var both = next != null;
        for (var j = 0; j <= (both ? 1 : 0); j++) {
            var bottomOrRight = plane.row == plane.mbHeight || plane.column == plane.mbWidth;
            if (!bottomOrRight) {
                plane.tilePosition(plane.column, plane.row);
                if (both) {
                    var other = j == 0 ? plane.next : this;
                    other.tileColumn = plane.tileColumn;
                    other.tileRow = plane.tileRow;
                }
                var context = plane.contexts[plane.tileColumn];
                plane.readPackets();
                plane.decodeDc(context);
                if (switches.decodeLp) {
                    plane.decodeLowpass(context);
                }
                plane.predictDcAc();
                plane.dequantize();
                if (switches.decodeHp) {
                    plane.decodeHighpass(context);
                    plane.predictAc();
                }
                plane.updatePredInfo();
            }
            if (header.subversion == JxrHeader.SUBVERSION) {
                plane.transform();
            } else {
                plane.transformAlternate();
            }
            if (both) {
                var other = j == 0 ? plane.next : this;
                other.row = plane.row;
                other.column = plane.column;
                plane = other;
            }
        }
    }

    /**
     * Finds the tile of a macroblock and whether it starts a tile or a context reset (getTilePos).
     */
    private void tilePosition(int x, int y) {
        if (x == 0) {
            tileColumn = 0;
        } else if (tileColumn < header.tileColumnsMinus1 && x == header.tileX[tileColumn + 1]) {
            tileColumn++;
        }
        if (y == 0) {
            tileRow = 0;
        } else if (tileRow < header.tileRowsMinus1 && y == header.tileY[tileRow + 1]) {
            tileRow++;
        }
        contextLeft = x == header.tileX[tileColumn];
        contextTop = y == header.tileY[tileRow];
        resetContext = resetTotals = ((x - header.tileX[tileColumn]) & 0xf) == 0;
        if (tileColumn == header.tileColumnsMinus1) {
            if (x + 1 == mbWidth) {
                resetContext = true;
            }
        } else if (x + 1 == header.tileX[tileColumn + 1]) {
            resetContext = true;
        }
    }

    /**
     * Attaches the packets of a new tile row, reads their headers and the tile headers of a new tile (readPackets).
     */
    private void readPackets() {
        if (column == 0 && row == header.tileY[tileRow]) {
            if (secondary) {
                if (streams.length > 0) {
                    for (var context : contexts) {
                        resetContext(context);
                    }
                } else {
                    resetContext(contexts[0]);
                }
            } else {
                for (var k = 0; k < streams.length; k++) {
                    var offset = indexTable[streams.length * tileRow + k] + headerSize;
                    if (offset < 0 || offset > end) {
                        throw new MediaException.Malformed("a JPEG XR packet offset past the codestream");
                    }
                    streams[k].attach((int) offset);
                }
                if (streams.length == 0) {
                    if (headerSize > end) {
                        throw new MediaException.Malformed("a JPEG XR packet offset past the codestream");
                    }
                    headerStream.attach((int) headerSize);
                }
                for (var k = 0; k < contexts.length; k++) {
                    var context = contexts[k];
                    if (!header.frequency) {
                        var stream = streams.length == 0 ? headerStream : streams[k];
                        packetHeader(stream, true);
                        context.trimFlexbits = header.trimFlexbits ? stream.read(4) : 0;
                    } else {
                        packetHeader(streams[k * bands], true);
                        if (bands > 1) {
                            packetHeader(streams[k * bands + 1], true);
                        }
                        if (bands > 2) {
                            packetHeader(streams[k * bands + 2], true);
                        }
                        if (bands > 3) {
                            var flexbits = streams[k * bands + 3];
                            packetHeader(flexbits, false);
                            context.trimFlexbits = header.trimFlexbits ? flexbits.read(4) : 0;
                        }
                    }
                    resetContext(context);
                }
            }
        }
        if (contextLeft && contextTop && !secondary) {
            var context = contexts[tileColumn];
            tileHeaderDc(context.dcStream);
            if (next != null) {
                next.tileHeaderDc(context.dcStream);
            }
            if (bands > 1) {
                tileHeaderLp(context.lpStream);
                if (next != null) {
                    next.tileHeaderLp(context.lpStream);
                }
            }
            if (bands > 2) {
                tileHeaderHp(context.acStream);
                if (next != null) {
                    next.tileHeaderHp(context.acStream);
                }
            }
        }
    }

    /**
     * Reads a packet header, the start code {@code 00 00 01} and a byte.
     *
     * @param strict whether a missing start code is an error
     */
    private static void packetHeader(JxrBits bits, boolean strict) {
        var valid = bits.read(8) == 0 && bits.read(8) == 0 && bits.read(8) == 1;
        bits.read(8);
        if (!valid && strict) {
            throw new MediaException.Malformed("a JPEG XR packet without a start code");
        }
    }

    /**
     * Reads the quantizer indices of every channel at one position.
     *
     * @return the channel mode
     */
    private int readQuantizer(Quantizer[][] quantizers, JxrBits bits, int index) {
        var mode = 0;
        if (channels > 1) {
            mode = bits.read(2);
        }
        quantizers[0][index].index = bits.read(8);
        if (mode == 1) {
            quantizers[1][index].index = bits.read(8);
        } else if (mode > 0) {
            for (var c = 1; c < channels; c++) {
                quantizers[c][index].index = bits.read(8);
            }
        }
        return mode;
    }

    /**
     * Reads the DC quantizers of a tile when they are not uniform (readTileHeaderDC).
     */
    private void tileHeaderDc(JxrBits bits) {
        if ((plane.qpMode & 1) != 0) {
            var tile = tiles[tileColumn];
            if (tileRow + tileColumn == 0) {
                for (var t : tiles) {
                    allocate(t.dc, 1);
                }
            }
            var mode = readQuantizer(tile.dc, bits, 0);
            format(tile.dc, mode, 0, true);
        }
    }

    /**
     * Reads the lowpass quantizers of a tile when they are not uniform (readTileHeaderLP).
     */
    private void tileHeaderLp(JxrBits bits) {
        if (plane.subband != JxrHeader.SB_DC_ONLY && (plane.qpMode & 2) != 0) {
            var tile = tiles[tileColumn];
            tile.useDc = bits.read(1) == 1;
            tile.bitsLp = 0;
            tile.numQpLp = 1;
            if (tile.useDc) {
                allocate(tile.lp, tile.numQpLp);
                useDcQuantizer(tileColumn);
            } else {
                tile.numQpLp = bits.read(4) + 1;
                tile.bitsLp = quantizerBits(tile.numQpLp);
                allocate(tile.lp, tile.numQpLp);
                for (var index = 0; index < tile.numQpLp; index++) {
                    var mode = readQuantizer(tile.lp, bits, index);
                    format(tile.lp, mode, index, true);
                }
            }
        }
    }

    /**
     * Reads the highpass quantizers of a tile when they are not uniform (readTileHeaderHP).
     */
    private void tileHeaderHp(JxrBits bits) {
        if (plane.subband != JxrHeader.SB_DC_ONLY && plane.subband != JxrHeader.SB_NO_HIGHPASS
                && (plane.qpMode & 4) != 0) {
            var tile = tiles[tileColumn];
            tile.useLp = bits.read(1) == 1;
            tile.bitsHp = 0;
            tile.numQpHp = 1;
            if (tile.useLp) {
                tile.numQpHp = tile.numQpLp;
                allocate(tile.hp, tile.numQpHp);
                if (tile.lp[0] == null || tile.lp[0].length < tile.numQpHp) {
                    throw new MediaException.Malformed("JPEG XR highpass quantizers taken from missing lowpass ones");
                }
                useLpQuantizer(tile.numQpHp, tileColumn);
            } else {
                tile.numQpHp = bits.read(4) + 1;
                tile.bitsHp = quantizerBits(tile.numQpHp);
                allocate(tile.hp, tile.numQpHp);
                for (var index = 0; index < tile.numQpHp; index++) {
                    var mode = readQuantizer(tile.hp, bits, index);
                    format(tile.hp, mode, index, false);
                }
            }
        }
    }

    /**
     * {@return the bits of a macroblock quantizer index for a number of quantizers (dquantBits)}
     */
    private static int quantizerBits(int count) {
        return count < 2 ? 0 : count < 4 ? 1 : count < 6 ? 2 : count < 10 ? 3 : 4;
    }

    /**
     * Reads a macroblock quantizer index: 0, or one more than the index that follows (decodeQPIndex).
     */
    private static int quantizerIndex(JxrBits bits, int count) {
        if (bits.read(1) == 0) {
            return 0;
        }
        return bits.read(count) + 1;
    }

    /**
     * Throws when a quantizer of a band was never defined.
     */
    private static Quantizer quantizer(Quantizer[][] quantizers, int channel, int index) {
        var list = quantizers[channel];
        if (list == null || index >= list.length) {
            throw new MediaException.Malformed("a JPEG XR macroblock with an undefined quantizer");
        }
        return list[index];
    }

    /**
     * Decodes the DC coefficients of a macroblock (DecodeMacroblockDC).
     */
    private void decodeDc(Context context) {
        var tile = tiles[tileColumn];
        var cf = colorFormat;
        for (var c = 0; c < channels; c++) {
            java.util.Arrays.fill(blockDc[c], 0);
        }
        var bits = context.dcStream;
        qIndexLp = qIndexHp = 0;
        if (!header.frequency && plane.subband != JxrHeader.SB_DC_ONLY) {
            if (tile.bitsLp > 0) {
                qIndexLp = quantizerIndex(bits, tile.bitsLp);
            }
            if (plane.subband != JxrHeader.SB_NO_HIGHPASS && tile.bitsHp > 0) {
                qIndexHp = quantizerIndex(bits, tile.bitsHp);
            }
        }
        if (tile.bitsHp == 0 && tile.numQpHp > 1) {
            qIndexHp = qIndexLp;
        }
        if (qIndexLp >= tile.numQpLp || qIndexHp >= tile.numQpHp) {
            throw new MediaException.Malformed("a JPEG XR macroblock quantizer index past the tile's quantizers");
        }
        var mean = laplacian;
        mean[0] = mean[1] = 0;
        var modelBits = context.dc.bits[0];
        if (cf == JxrHeader.Y_ONLY || cf == JxrHeader.CMYK || cf == JxrHeader.NCOMPONENT) {
            var slot = 0;
            for (var c = 0; c < channels; c++) {
                var q = 0;
                if (bits.bit() != 0) {
                    q = absoluteLevel(context.codes[3], bits) - 1;
                    mean[slot]++;
                }
                if (modelBits != 0) {
                    q = (q << modelBits) | bits.read(modelBits);
                }
                if (q != 0 && bits.bit() != 0) {
                    q = -q;
                }
                blockDc[c][0] = q;
                slot = 1;
                modelBits = context.dc.bits[1];
            }
        } else {
            var index = context.codes[2].decode(bits);
            var qy = index >> 2;
            var qu = (index >> 1) & 1;
            var qv = index & 1;
            if (qy != 0) {
                qy = absoluteLevel(context.codes[3], bits) - 1;
                mean[0]++;
            }
            if (modelBits != 0) {
                qy = (qy << modelBits) | bits.read(modelBits);
            }
            if (qy != 0 && bits.bit() != 0) {
                qy = -qy;
            }
            blockDc[0][0] = qy;
            modelBits = context.dc.bits[1];
            if (qu != 0) {
                qu = absoluteLevel(context.codes[4], bits) - 1;
                mean[1]++;
            }
            if (modelBits != 0) {
                qu = (qu << modelBits) | bits.read(modelBits);
            }
            if (qu != 0 && bits.bit() != 0) {
                qu = -qu;
            }
            blockDc[1][0] = qu;
            if (qv != 0) {
                qv = absoluteLevel(context.codes[4], bits) - 1;
                mean[1]++;
            }
            if (modelBits != 0) {
                qv = (qv << modelBits) | bits.read(modelBits);
            }
            if (qv != 0 && bits.bit() != 0) {
                qv = -qv;
            }
            blockDc[2][0] = qv;
        }
        updateModel(cf, channels, mean, context.dc);
        if (plane.subband == JxrHeader.SB_DC_ONLY && resetContext) {
            for (var k = 2; k < 5; k++) {
                context.codes[k].adapt();
            }
        }
    }

    /**
     * The Laplacian means of a macroblock band.
     */
    private final int[] laplacian = new int[2];

    /**
     * The run-level pairs of a block.
     */
    private final int[] runLevels = new int[40];

    /**
     * The coefficients of a joint chroma lowpass block.
     */
    private final int[] chromaTemp = new int[16];

    /**
     * Decodes the lowpass coefficients of a macroblock (DecodeMacroblockLowpass).
     */
    private void decodeLowpass(Context context) {
        var cf = colorFormat;
        var fullPlanes = cf == JxrHeader.YUV_420 || cf == JxrHeader.YUV_422 ? 2 : channels;
        var scan = context.lowpass;
        var bits = context.lpStream;
        var modelBits = context.lp.bits[0];
        var rl = runLevels;
        var mean = laplacian;
        mean[0] = mean[1] = 0;
        var slot = 0;
        var tile = tiles[tileColumn];
        if (header.frequency && tile.bitsLp > 0) {
            qIndexLp = quantizerIndex(bits, tile.bitsLp);
        }
        if (resetTotals) {
            var weight = 32;
            scan.total[0] = MAXTOTAL;
            for (var k = 1; k < 16; k++) {
                scan.total[k] = weight;
                weight -= 2;
            }
        }
        var cbpLp = 0;
        if (cf == JxrHeader.YUV_420 || cf == JxrHeader.YUV_422 || cf == JxrHeader.YUV_444) {
            var countMax = context.cbpCountMax;
            var countZero = context.cbpCountZero;
            var max = fullPlanes * 4 - 5;
            if (countZero <= 0 || countMax < 0) {
                if (bits.bit() != 0) {
                    cbpLp = 1;
                    var k = bits.read(fullPlanes - 1);
                    if (k != 0) {
                        cbpLp = k * 2 + bits.read(1);
                    }
                }
                if (countMax < countZero) {
                    cbpLp = max - cbpLp;
                }
            } else {
                cbpLp = bits.read(fullPlanes);
            }
            countMax += 1 - 4 * (cbpLp == max ? 1 : 0);
            countZero += 1 - 4 * (cbpLp == 0 ? 1 : 0);
            context.cbpCountMax = Math.clamp(countMax, -8, 7);
            context.cbpCountZero = Math.clamp(countZero, -8, 7);
        } else {
            for (var c = 0; c < channels; c++) {
                cbpLp |= bits.read(1) << c;
            }
        }
        var chromaPair = cf == JxrHeader.YUV_420 || cf == JxrHeader.YUV_422;
        for (var c = 0; c < fullPlanes; c++) {
            var coeffs = blockDc[c];
            if ((cbpLp & 1) != 0) {
                var location = 1 + (cf == JxrHeader.YUV_420 && c == 1 ? 9 : 0)
                        + (cf == JxrHeader.YUV_422 && c == 1 ? 1 : 0);
                var nonzero = decodeBlock(c > 0, rl, context.codes, CTDC, bits, location);
                mean[slot] += nonzero;
                if (chromaPair && c != 0) {
                    var temp = chromaTemp;
                    java.util.Arrays.fill(temp, 0);
                    var index = 0;
                    for (var k = 0; k < nonzero; k++) {
                        index += rl[k * 2];
                        temp[index & 0xf] = rl[k * 2 + 1];
                        index++;
                    }
                    var remap = cf == JxrHeader.YUV_420 ? 1 : 0;
                    var count = cf == JxrHeader.YUV_420 ? 6 : 14;
                    for (var k = 0; k < count; k++) {
                        blockDc[(k & 1) + 1][CHROMA_REMAP[remap + (k >> 1)]] = temp[k];
                    }
                } else {
                    var index = 1;
                    for (var k = 0; k < nonzero; k++) {
                        index += rl[k * 2];
                        if (index > 15) {
                            throw new MediaException.Malformed("a JPEG XR lowpass run past its block");
                        }
                        coeffs[scan.scan[index]] = rl[k * 2 + 1];
                        scan.total[index]++;
                        if (scan.total[index] > scan.total[index - 1]) {
                            scan.swap(index);
                        }
                        index++;
                    }
                }
            }
            if (modelBits != 0) {
                if (chromaPair && c != 0) {
                    var limit = cf == JxrHeader.YUV_420 ? 4 : 8;
                    for (var k = 1; k < limit; k++) {
                        blockDc[1][k] = refine(blockDc[1][k], bits, modelBits);
                        blockDc[2][k] = refine(blockDc[2][k], bits, modelBits);
                    }
                } else {
                    for (var k = 1; k < 16; k++) {
                        if (coeffs[k] > 0) {
                            coeffs[k] = (coeffs[k] << modelBits) + bits.read(modelBits);
                        } else if (coeffs[k] < 0) {
                            coeffs[k] = (coeffs[k] << modelBits) - bits.read(modelBits);
                        } else {
                            coeffs[k] = bits.signedMagnitude(modelBits);
                        }
                    }
                }
            }
            slot = 1;
            modelBits = context.lp.bits[1];
            cbpLp >>= 1;
        }
        updateModel(cf, channels, mean, context.lp);
        if (resetContext) {
            adaptLowpass(context);
        }
    }

    /** The positions of the joint chroma lowpass coefficients. */
    private static final int[] CHROMA_REMAP = {4, 1, 2, 3, 5, 6, 7};

    /**
     * Appends the refinement bits of a joint chroma lowpass coefficient.
     */
    private static int refine(int value, JxrBits bits, int modelBits) {
        if (value > 0) {
            return (value << modelBits) + bits.read(modelBits);
        }
        if (value < 0) {
            return (value << modelBits) - bits.read(modelBits);
        }
        value = bits.read(modelBits);
        if (value != 0 && bits.bit() != 0) {
            value = -value;
        }
        return value;
    }

    /**
     * Decodes the magnitude of a significant coefficient (DecodeSignificantAbsLevel).
     */
    private static int absoluteLevel(JxrHuffman code, JxrBits bits) {
        var index = code.decode(bits);
        if (index > 6) {
            throw new MediaException.Malformed("invalid JPEG XR level code");
        }
        code.update(index);
        if (index < 2) {
            return index + 2;
        }
        if (index < 6) {
            return LEVEL_REMAP[index] + bits.read(LEVEL_FIXED[index]);
        }
        var fixed = bits.read(4) + 4;
        if (fixed == 19) {
            fixed += bits.read(2);
            if (fixed == 22) {
                fixed += bits.read(3);
            }
        }
        return 2 + (1 << fixed) + bits.read(fixed);
    }

    /** The base of the significant level codes. */
    private static final int[] LEVEL_REMAP = {2, 3, 4, 6, 10, 14};

    /** The fixed-length bits of the significant level codes. */
    private static final int[] LEVEL_FIXED = {0, 0, 1, 2, 2, 2};

    /**
     * Decodes the length of a run of zeros before a significant coefficient (DecodeSignificantRun).
     */
    private static int significantRun(int maxRun, JxrHuffman code, JxrBits bits) {
        if (maxRun < 5) {
            if (maxRun == 1) {
                return 1;
            } else if (bits.bit() != 0) {
                return 1;
            } else if (maxRun == 2 || bits.bit() != 0) {
                return 2;
            } else if (maxRun == 3 || bits.bit() != 0) {
                return 3;
            }
            return 4;
        }
        if (maxRun > 14) {
            throw new MediaException.Malformed("a JPEG XR run past its block");
        }
        var index = code.decodeShort(bits) + RUN_BIN[maxRun] * 5;
        if (index < 0 || index >= RUN_REMAP.length) {
            throw new MediaException.Malformed("invalid JPEG XR run code");
        }
        var run = RUN_REMAP[index];
        var fixed = RUN_FIXED_LENGTH[index];
        if (fixed != 0) {
            run += bits.read(fixed);
        }
        return run;
    }

    /**
     * Decodes the index of a coefficient after the first of a block (DecodeIndex).
     */
    private static int decodeIndex(int location, JxrHuffman code, JxrBits bits) {
        if (location < 15) {
            var index = code.decodeShort(bits);
            code.updateBoth(index);
            return index;
        }
        if (location == 15) {
            if (bits.bit() == 0) {
                return 0;
            }
            if (bits.bit() == 0) {
                return 2;
            }
            return 1 + 2 * bits.bit();
        }
        return bits.read(1);
    }

    /**
     * Decodes the run-level pairs of a lowpass block (DecodeBlock).
     *
     * @return the number of significant coefficients
     */
    private static int decodeBlock(boolean chroma, int[] rl, JxrHuffman[] codes, int contextOffset, JxrBits bits,
                                   int location) {
        var base = contextOffset + (chroma ? 3 : 0);
        var index = codes[base].decode(bits);
        codes[base].updateBoth(index);
        var sr = index & 1;
        var srn = index >> 2;
        var cont = sr & srn;
        var sign = bits.sign();
        if ((index & 2) != 0) {
            rl[1] = (absoluteLevel(codes[6 + contextOffset + cont], bits) ^ sign) - sign;
        } else {
            rl[1] = 1 | sign;
        }
        rl[0] = 0;
        if (sr == 0) {
            rl[0] = significantRun(15 - location, codes[0], bits);
        }
        location += rl[0] + 1;
        var nonzero = 1;
        while (srn != 0) {
            if (nonzero >= 16) {
                throw new MediaException.Malformed("a JPEG XR block of more than 16 coefficients");
            }
            sr = srn & 1;
            rl[nonzero * 2] = 0;
            if (sr == 0) {
                rl[nonzero * 2] = significantRun(15 - location, codes[0], bits);
            }
            location += rl[nonzero * 2] + 1;
            index = decodeIndex(location, codes[base + cont + 1], bits);
            srn = index >> 1;
            cont &= srn;
            sign = bits.sign();
            if ((index & 1) != 0) {
                rl[nonzero * 2 + 1] = (absoluteLevel(codes[6 + contextOffset + cont], bits) ^ sign) - sign;
            } else {
                rl[nonzero * 2 + 1] = 1 | sign;
            }
            nonzero++;
        }
        return nonzero;
    }

    /**
     * Decodes the highpass coefficients of a macroblock (DecodeMacroblockHighpass).
     */
    private void decodeHighpass(Context context) {
        if (resetTotals) {
            var weight = 32;
            context.horizontal.total[0] = context.vertical.total[0] = MAXTOTAL;
            for (var k = 1; k < 16; k++) {
                context.horizontal.total[k] = context.vertical.total[k] = weight;
                weight -= 2;
            }
        }
        var tile = tiles[tileColumn];
        if (header.frequency && tile.bitsHp > 0) {
            qIndexHp = quantizerIndex(context.acStream, tile.bitsHp);
            if (qIndexHp >= tile.numQpHp) {
                throw new MediaException.Malformed("a JPEG XR macroblock quantizer index past the tile's"
                        + " quantizers");
            }
        } else if (tile.bitsHp == 0 && tile.numQpHp > 1) {
            qIndexHp = qIndexLp;
        }
        decodeCbp(context);
        predictCbp(context);
        decodeCoefficients(context);
        if (resetContext) {
            adaptHighpass(context);
        }
    }

    /** The coded block pattern codes of two bits plus one. */
    private static final int[] CBP_TABLE = {6, 9, 10, 12};

    /** The fixed-length bits of the block CBP codes. */
    private static final int[] CBP_FLC = {0, 2, 1, 2, 2, 0};

    /** The offset of the block CBP codes. */
    private static final int[] CBP_OFFSET = {0, 4, 2, 8, 12, 1};

    /** The block CBP of each code. */
    private static final int[] CBP_OUT = {0, 15, 3, 12, 1, 2, 4, 8, 5, 6, 9, 10, 7, 11, 13, 14};

    /** The positions of the 4:2:2 chroma CBP bits of each block. */
    private static final int[] CBP_SHIFT_422 = {0, 1, 4, 5};

    /**
     * Decodes the coded block pattern differences of a macroblock (DecodeCBP).
     */
    private void decodeCbp(Context context) {
        var bits = context.acStream;
        var cf = colorFormat;
        var count = cf == JxrHeader.NCOMPONENT || cf == JxrHeader.CMYK ? channels : 1;
        for (var c = 0; c < count; c++) {
            var cy = 0;
            var cu = 0;
            var cv = 0;
            var num = context.cbpY1.decodeShort(bits);
            if (num < 0 || num > 4) {
                throw new MediaException.Malformed("invalid JPEG XR coded block pattern code");
            }
            context.cbpY1.update(num);
            num = switch (num) {
                case 2 -> {
                    var v = bits.read(2);
                    if (v == 0) {
                        yield 3;
                    } else if (v == 1) {
                        yield 5;
                    }
                    yield CBP_TABLE[v * 2 + bits.bit() - 4];
                }
                case 1 -> 1 << bits.read(2);
                case 3 -> 0xf ^ (1 << bits.read(2));
                case 4 -> 0xf;
                default -> 0;
            };
            for (var block = 0; block < 4; block++) {
                if ((num & (1 << block)) == 0) {
                    continue;
                }
                var symbol = context.cbpY.decode(bits);
                if (symbol < 0 || symbol > 8) {
                    throw new MediaException.Malformed("invalid JPEG XR block pattern code");
                }
                var value = symbol + 1;
                context.cbpY.update(symbol);
                var blockCbp = 0;
                if (value >= 6) {
                    if (bits.bit() != 0) {
                        blockCbp = 0x10;
                    } else if (bits.bit() != 0) {
                        blockCbp = 0x20;
                    } else {
                        blockCbp = 0x30;
                    }
                    if (value == 9) {
                        if (bits.bit() == 0) {
                            value = bits.bit() != 0 ? 10 : 11;
                        }
                    }
                    value -= 6;
                }
                var code = CBP_OFFSET[value];
                if (CBP_FLC[value] != 0) {
                    code += bits.read(CBP_FLC[value]);
                }
                blockCbp += CBP_OUT[code];
                switch (cf) {
                    case JxrHeader.YUV_444 -> {
                        cy |= (blockCbp & 0xf) << (block * 4);
                        for (var k = 0; k < 2; k++) {
                            if (((blockCbp >> (k + 4)) & 1) != 0) {
                                var chroma = context.codes[1].decodeShort(bits);
                                chroma = switch (chroma) {
                                    case 1 -> {
                                        var v = bits.read(2);
                                        if (v == 0) {
                                            yield 3;
                                        } else if (v == 1) {
                                            yield 5;
                                        }
                                        yield CBP_TABLE[v * 2 + bits.bit() - 4];
                                    }
                                    case 0 -> 1 << bits.read(2);
                                    case 2 -> 0xf ^ (1 << bits.read(2));
                                    default -> 0xf;
                                };
                                if (k == 0) {
                                    cu |= chroma << (block * 4);
                                } else {
                                    cv |= chroma << (block * 4);
                                }
                            }
                        }
                    }
                    case JxrHeader.YUV_420 -> {
                        cy |= (blockCbp & 0xf) << (block * 4);
                        cu |= ((blockCbp >> 4) & 1) << block;
                        cv |= ((blockCbp >> 5) & 1) << block;
                    }
                    case JxrHeader.YUV_422 -> {
                        cy |= (blockCbp & 0xf) << (block * 4);
                        for (var k = 0; k < 2; k++) {
                            if (((blockCbp >> (k + 4)) & 1) != 0) {
                                var chroma = 5;
                                if (bits.bit() != 0) {
                                    chroma = 1;
                                } else if (bits.bit() != 0) {
                                    chroma = 4;
                                }
                                chroma <<= CBP_SHIFT_422[block];
                                if (k == 0) {
                                    cu |= chroma;
                                } else {
                                    cv |= chroma;
                                }
                            }
                        }
                    }
                    default -> cy |= blockCbp << (block * 4);
                }
            }
            diffCbp[c] = cy;
            if (cf == JxrHeader.YUV_420 || cf == JxrHeader.YUV_444 || cf == JxrHeader.YUV_422) {
                diffCbp[1] = cu;
                diffCbp[2] = cv;
            }
        }
    }

    /**
     * Decodes the highpass coefficients of every block (DecodeCoeffs).
     */
    private void decodeCoefficients(Context context) {
        var bits = context.acStream;
        var flexbits = context.flStream;
        var tile = tiles[tileColumn];
        var cf = colorFormat;
        var planesCount = cf == JxrHeader.YUV_420 || cf == JxrHeader.YUV_422 ? 1 : channels;
        var blocks = 4;
        var modelBits = context.ac.bits[0];
        var mean = laplacian;
        mean[0] = mean[1] = 0;
        var slot = 0;
        var order = DCT_INDEX[0];
        var chroma = false;
        var cy = cbp[0];
        var scan = orientation == 1 ? context.vertical : context.horizontal;
        if (cf == JxrHeader.YUV_420) {
            blocks = 6;
            cy += (cbp[1] << 16) + (cbp[2] << 20);
        } else if (cf == JxrHeader.YUV_422) {
            blocks = 8;
            cy += (cbp[1] << 16) + (cbp[2] << 24);
        }
        for (var i = 0; i < planesCount; i++) {
            var index = 0;
            for (var block = 0; block < blocks; block++) {
                var channel = planesCount > 1 ? i : block > 3 ? (cf == JxrHeader.YUV_420 ? block - 3
                        : block / 2 - 1) : 0;
                var qp = quantizer(tile.hp, channel, qIndexHp).qp;
                for (var sub = 0; sub < 4; sub++, index++, cy >>= 1) {
                    int target;
                    int offset;
                    if (block >= 4) {
                        if (cf == JxrHeader.YUV_420) {
                            target = block - 3;
                            offset = current[target] + column * stride[target] + BLOCK_OFFSET_UV[sub];
                        } else {
                            target = 1 + (1 & (block >> 1));
                            offset = current[target] + column * stride[target] + (block & 1) * 32
                                    + BLOCK_OFFSET_UV_422[sub];
                        }
                    } else {
                        target = i;
                        offset = current[i] + column * stride[i] + BLOCK_OFFSET[index & 0xf];
                    }
                    var nonzero = decodeBlockAdaptive((cy & 1) != 0, chroma, context.codes, bits, flexbits,
                            rows[target], offset, scan, modelBits, context.trimFlexbits, qp, order);
                    if (nonzero > 16) {
                        throw new MediaException.Malformed("a JPEG XR block of more than 16 coefficients");
                    }
                    mean[slot] += nonzero;
                }
                if (block == 3) {
                    modelBits = context.ac.bits[1];
                    slot = 1;
                    chroma = true;
                }
            }
            cy = cbp[(i + 1) & 0xf];
        }
        updateModel(cf, channels, mean, context.ac);
    }

    /**
     * Decodes the highpass coefficients of a block and their flexbits (DecodeBlockAdaptive).
     */
    private int decodeBlockAdaptive(boolean coded, boolean chroma, JxrHuffman[] codes, JxrBits bits,
                                    JxrBits flexbits, int[] buffer, int offset, Scan scan, int modelBits, int trim,
                                    int qp, int[] order) {
        var flex = modelBits - trim;
        if (flex < 0 || switches.skipFlexbits) {
            flex = 0;
        }
        var nonzero = 0;
        if (coded) {
            nonzero = decodeBlockHighpass(chroma, codes, bits, qp << modelBits, buffer, offset, scan);
        }
        if (flex != 0) {
            if (qp + trim == 1) {
                for (var k = 1; k < 16; k++) {
                    var at = offset + order[k];
                    if (buffer[at] < 0) {
                        buffer[at] -= flexbits.read(flex);
                    } else if (buffer[at] > 0) {
                        buffer[at] += flexbits.read(flex);
                    } else {
                        buffer[at] = flexbits.signedMagnitude(flex);
                    }
                }
            } else {
                var qp1 = qp << trim;
                for (var k = 1; k < 16; k++) {
                    var at = offset + order[k];
                    if (buffer[at] < 0) {
                        buffer[at] -= qp1 * flexbits.read(flex);
                    } else if (buffer[at] > 0) {
                        buffer[at] += qp1 * flexbits.read(flex);
                    } else {
                        buffer[at] = qp1 * flexbits.signedMagnitude(flex);
                    }
                }
            }
        }
        return nonzero;
    }

    /**
     * Decodes and dequantizes the highpass coefficients of a block (DecodeBlockHighpass).
     */
    private static int decodeBlockHighpass(boolean chroma, JxrHuffman[] codes, JxrBits bits, int qp, int[] buffer,
                                           int offset, Scan scan) {
        var contextOffset = CTDC + CONTEXTX;
        var base = contextOffset + (chroma ? 3 : 0);
        var location = 1;
        var index = codes[base].decode(bits);
        codes[base].updateBoth(index);
        var sr = index & 1;
        var srn = index >> 2;
        var cont = sr & srn;
        var sign = bits.sign();
        var level = (qp ^ sign) - sign;
        if ((index & 2) != 0) {
            level *= absoluteLevel(codes[6 + contextOffset + cont], bits);
        }
        if (sr == 0) {
            location += significantRun(15 - location, codes[0], bits);
        }
        location &= 0xf;
        buffer[offset + scan.scan[location]] = level;
        scan.total[location]++;
        if (location != 0 && scan.total[location] > scan.total[location - 1]) {
            scan.swap(location);
        }
        location = (location + 1) & 0xf;
        var nonzero = 1;
        while (srn != 0) {
            if (nonzero >= 16) {
                return 17;
            }
            sr = srn & 1;
            if (sr == 0) {
                location += significantRun(15 - location, codes[0], bits);
                if (location >= 16) {
                    return 16;
                }
            }
            index = decodeIndex(location + 1, codes[base + cont + 1], bits);
            srn = index >> 1;
            cont &= srn;
            sign = bits.sign();
            level = (qp ^ sign) - sign;
            if ((index & 1) != 0) {
                level *= absoluteLevel(codes[6 + contextOffset + cont], bits);
            }
            buffer[offset + scan.scan[location]] = level;
            scan.total[location]++;
            if (location != 0 && scan.total[location] > scan.total[location - 1]) {
                scan.swap(location);
            }
            location = (location + 1) & 0xf;
            nonzero++;
        }
        return nonzero;
    }

    /**
     * Dequantizes the DC and lowpass coefficients of the current macroblock into its buffers
     * (dequantizeMacroblock).
     */
    private void dequantize() {
        var cf = colorFormat;
        var tile = tiles[tileColumn];
        for (var c = 0; c < channels; c++) {
            var buffer = rows[c];
            var p = current[c] + column * stride[c];
            var dc = blockDc[c];
            buffer[p] = dc[0] * quantizer(tile.dc, c, 0).qp;
            if (plane.subband == JxrHeader.SB_DC_ONLY) {
                continue;
            }
            var qp = quantizer(tile.lp, c, qIndexLp).qp;
            if (c == 0 || (cf != JxrHeader.YUV_422 && cf != JxrHeader.YUV_420)) {
                for (var k = 1; k < 16; k++) {
                    buffer[p + DCT_INDEX[2][k]] = dc[k] * qp;
                }
            } else if (cf == JxrHeader.YUV_422) {
                buffer[p + 64] = dc[1] * qp;
                buffer[p + 16] = dc[2] * qp;
                buffer[p + 80] = dc[3] * qp;
                buffer[p + 32] = dc[4] * qp;
                buffer[p + 96] = dc[5] * qp;
                buffer[p + 48] = dc[6] * qp;
                buffer[p + 112] = dc[7] * qp;
            } else {
                buffer[p + 32] = dc[1] * qp;
                buffer[p + 16] = dc[2] * qp;
                buffer[p + 48] = dc[3] * qp;
            }
        }
    }

    /**
     * {@return the DC and AD prediction modes of the current macroblock, the DC mode in bits 0-1 and the AD mode
     * in bits 2-3 (getDCACPredMode)}
     */
    private int dcAcPredictionMode() {
        int dcMode;
        var adMode = 2;
        var x = column;
        if (contextLeft && contextTop) {
            dcMode = 3;
        } else if (contextLeft) {
            dcMode = 1;
        } else if (contextTop) {
            dcMode = 0;
        } else {
            var left = pred[0][x - 1].dc;
            var top = predPrevious[0][x].dc;
            var topLeft = predPrevious[0][x - 1].dc;
            int strH;
            int strV;
            if (colorFormat == JxrHeader.Y_ONLY || colorFormat == JxrHeader.NCOMPONENT) {
                strH = Math.abs(topLeft - left);
                strV = Math.abs(topLeft - top);
            } else {
                var scale = colorFormat == JxrHeader.YUV_420 ? 8 : colorFormat == JxrHeader.YUV_422 ? 4 : 2;
                strH = Math.abs(topLeft - left) * scale
                        + Math.abs(predPrevious[1][x - 1].dc - pred[1][x - 1].dc)
                        + Math.abs(predPrevious[2][x - 1].dc - pred[2][x - 1].dc);
                strV = Math.abs(topLeft - top) * scale
                        + Math.abs(predPrevious[1][x - 1].dc - predPrevious[1][x].dc)
                        + Math.abs(predPrevious[2][x - 1].dc - predPrevious[2][x].dc);
            }
            dcMode = strH * ORIENT_WEIGHT < strV ? 1 : strV * ORIENT_WEIGHT < strH ? 0 : 2;
        }
        if (dcMode == 1 && qIndexLp == predPrevious[0][x].qpIndex) {
            adMode = 1;
        }
        if (dcMode == 0 && qIndexLp == pred[0][x - 1].qpIndex) {
            adMode = 0;
        }
        return dcMode + (adMode << 2);
    }

    /**
     * {@return the highpass prediction direction of the current macroblock: 0 from the left, 1 from the top, 2
     * none (getACPredMode)}
     */
    private int acPredictionMode() {
        var y = blockDc[0];
        var strH = Math.abs(y[1]) + Math.abs(y[2]) + Math.abs(y[3]);
        var strV = Math.abs(y[4]) + Math.abs(y[8]) + Math.abs(y[12]);
        var cf = colorFormat;
        if (cf != JxrHeader.Y_ONLY && cf != JxrHeader.NCOMPONENT) {
            var u = blockDc[1];
            var v = blockDc[2];
            strH += Math.abs(u[1]) + Math.abs(v[1]);
            if (cf == JxrHeader.YUV_420) {
                strV += Math.abs(u[2]) + Math.abs(v[2]);
            } else if (cf == JxrHeader.YUV_422) {
                strV += Math.abs(u[2]) + Math.abs(v[2]) + Math.abs(u[6]) + Math.abs(v[6]);
                strH += Math.abs(u[5]) + Math.abs(v[5]);
            } else {
                strV += Math.abs(u[4]) + Math.abs(v[4]);
            }
        }
        return strH * ORIENT_WEIGHT < strV ? 1 : strV * ORIENT_WEIGHT < strH ? 0 : 2;
    }

    /**
     * Undoes the DC and lowpass prediction of the current macroblock (predDCACDec).
     */
    private void predictDcAc() {
        var cf = colorFormat;
        var count = cf == JxrHeader.YUV_420 || cf == JxrHeader.YUV_422 ? 1 : channels;
        var x = column;
        var mode = dcAcPredictionMode();
        var dcMode = mode & 0x3;
        var adMode = mode & 0xC;
        for (var c = 0; c < count; c++) {
            var org = blockDc[c];
            if (dcMode == 1) {
                org[0] += predPrevious[c][x].dc;
            } else if (dcMode == 0) {
                org[0] += pred[c][x - 1].dc;
            } else if (dcMode == 2) {
                org[0] += (pred[c][x - 1].dc + predPrevious[c][x].dc) >> 1;
            }
            if (adMode == 4) {
                var ref = predPrevious[c][x].ad;
                org[4] += ref[3];
                org[8] += ref[4];
                org[12] += ref[5];
            } else if (adMode == 0) {
                var ref = pred[c][x - 1].ad;
                org[1] += ref[0];
                org[2] += ref[1];
                org[3] += ref[2];
            }
        }
        if (cf == JxrHeader.YUV_420) {
            for (var c = 1; c < 3; c++) {
                var org = blockDc[c];
                if (dcMode == 1) {
                    org[0] += predPrevious[c][x].dc;
                } else if (dcMode == 0) {
                    org[0] += pred[c][x - 1].dc;
                } else if (dcMode == 2) {
                    org[0] += (pred[c][x - 1].dc + predPrevious[c][x].dc + 1) >> 1;
                }
                if (adMode == 4) {
                    org[2] += predPrevious[c][x].ad[1];
                } else if (adMode == 0) {
                    org[1] += pred[c][x - 1].ad[0];
                }
            }
        } else if (cf == JxrHeader.YUV_422) {
            for (var c = 1; c < 3; c++) {
                var org = blockDc[c];
                if (dcMode == 1) {
                    org[0] += predPrevious[c][x].dc;
                } else if (dcMode == 0) {
                    org[0] += pred[c][x - 1].dc;
                } else if (dcMode == 2) {
                    org[0] += (pred[c][x - 1].dc + predPrevious[c][x].dc + 1) >> 1;
                }
                if (adMode == 4) {
                    org[4] += predPrevious[c][x].ad[4];
                    org[2] += predPrevious[c][x].ad[3];
                    org[6] += org[2];
                } else if (adMode == 0) {
                    org[4] += pred[c][x - 1].ad[4];
                    org[1] += pred[c][x - 1].ad[0];
                    org[5] += pred[c][x - 1].ad[2];
                } else if (dcMode == 1) {
                    org[6] += org[2];
                }
            }
        }
        orientation = 2 - acPredictionMode();
    }

    /** The 4x4 blocks predicted from the block above, by block index. */
    private static final int[] TOP_BLOCKS = {1, 2, 3, 5, 6, 7, 9, 10, 11, 13, 14, 15};

    /**
     * Undoes the highpass prediction of the current macroblock (predACDec).
     */
    private void predictAc() {
        var cf = colorFormat;
        var count = cf == JxrHeader.YUV_420 || cf == JxrHeader.YUV_422 ? 1 : channels;
        var mode = 2 - orientation;
        for (var c = 0; c < count; c++) {
            var buffer = rows[c];
            var src = current[c] + column * stride[c];
            if (mode == 1) {
                for (var block : TOP_BLOCKS) {
                    var org = src + 16 * block;
                    var ref = org - 16;
                    buffer[org + 2] += buffer[ref + 2];
                    buffer[org + 10] += buffer[ref + 10];
                    buffer[org + 9] += buffer[ref + 9];
                }
            } else if (mode == 0) {
                for (var j = 64; j < 256; j += 16) {
                    var org = src + j;
                    var ref = org - 64;
                    buffer[org + 1] += buffer[ref + 1];
                    buffer[org + 5] += buffer[ref + 5];
                    buffer[org + 6] += buffer[ref + 6];
                }
            }
        }
        if (cf == JxrHeader.YUV_420) {
            for (var c = 1; c < 3; c++) {
                var buffer = rows[c];
                var src = current[c] + column * stride[c];
                if (mode == 1) {
                    for (var j = 1; j <= 3; j += 2) {
                        var org = src + 16 * j;
                        var ref = org - 16;
                        buffer[org + 2] += buffer[ref + 2];
                        buffer[org + 10] += buffer[ref + 10];
                        buffer[org + 9] += buffer[ref + 9];
                    }
                } else if (mode == 0) {
                    for (var j = 2; j <= 3; j++) {
                        var org = src + 16 * j;
                        var ref = org - 32;
                        buffer[org + 1] += buffer[ref + 1];
                        buffer[org + 5] += buffer[ref + 5];
                        buffer[org + 6] += buffer[ref + 6];
                    }
                }
            }
        } else if (cf == JxrHeader.YUV_422) {
            for (var c = 1; c < 3; c++) {
                var buffer = rows[c];
                var src = current[c] + column * stride[c];
                if (mode == 1) {
                    for (var j = 2; j < 8; j++) {
                        var org = src + BLOCK_OFFSET_UV_422[j];
                        var ref = org - 16;
                        buffer[org + 10] += buffer[ref + 10];
                        buffer[org + 2] += buffer[ref + 2];
                        buffer[org + 9] += buffer[ref + 9];
                    }
                } else if (mode == 0) {
                    for (var j = 1; j < 8; j += 2) {
                        var org = src + BLOCK_OFFSET_UV_422[j];
                        var ref = org - 64;
                        buffer[org + 1] += buffer[ref + 1];
                        buffer[org + 5] += buffer[ref + 5];
                        buffer[org + 6] += buffer[ref + 6];
                    }
                }
            }
        }
    }

    /**
     * Saves the prediction information of the current macroblock (updatePredInfo).
     */
    private void updatePredInfo() {
        var cf = colorFormat;
        var count = cf == JxrHeader.YUV_420 || cf == JxrHeader.YUV_422 ? 1 : channels;
        var x = column;
        for (var c = 0; c < count; c++) {
            var info = pred[c][x];
            var p = blockDc[c];
            info.dc = p[0];
            info.qpIndex = qIndexLp;
            info.ad[0] = p[1];
            info.ad[1] = p[2];
            info.ad[2] = p[3];
            info.ad[3] = p[4];
            info.ad[4] = p[8];
            info.ad[5] = p[12];
        }
        if (cf == JxrHeader.YUV_420) {
            for (var c = 1; c < 3; c++) {
                var info = pred[c][x];
                var p = blockDc[c];
                info.dc = p[0];
                info.qpIndex = qIndexLp;
                info.ad[0] = p[1];
                info.ad[1] = p[2];
            }
        } else if (cf == JxrHeader.YUV_422) {
            for (var c = 1; c < 3; c++) {
                var info = pred[c][x];
                var p = blockDc[c];
                info.qpIndex = qIndexLp;
                info.dc = p[0];
                info.ad[0] = p[1];
                info.ad[1] = p[2];
                info.ad[2] = p[5];
                info.ad[3] = p[6];
                info.ad[4] = p[4];
            }
        }
    }

    /**
     * Undoes the prediction of the coded block patterns of the current macroblock (predCBPDec).
     */
    private void predictCbp(Context context) {
        var cf = colorFormat;
        var count = cf == JxrHeader.YUV_420 || cf == JxrHeader.YUV_422 ? 1 : channels;
        var x = column;
        for (var c = 0; c < count; c++) {
            pred[c][x].cbp = cbp[c] = predictCbpChannel(diffCbp[c], x, c, context);
        }
        if (cf == JxrHeader.YUV_422) {
            for (var c = 1; c < 3; c++) {
                pred[c][x].cbp = cbp[c] = predictCbpChroma(diffCbp[c], x, c, context, 6, 0xff, 2);
            }
        } else if (cf == JxrHeader.YUV_420) {
            for (var c = 1; c < 3; c++) {
                pred[c][x].cbp = cbp[c] = predictCbpChroma(diffCbp[c], x, c, context, 2, 0xf, 4);
            }
        }
    }

    /**
     * {@return the number of set bits of the low 16 bits}
     */
    private static int ones(int value) {
        return Integer.bitCount(value & 0xffff);
    }

    /**
     * Updates the state of a CBP model from the number of coded blocks.
     */
    private static void updateCbpModel(Context context, int slot, int coded) {
        context.count0[slot] = saturate(context.count0[slot] + coded - 3);
        context.count1[slot] = saturate(context.count1[slot] + 16 - coded - 3);
        if (context.count0[slot] < 0) {
            context.cbpState[slot] = context.count0[slot] < context.count1[slot] ? 1 : 2;
        } else if (context.count1[slot] < 0) {
            context.cbpState[slot] = 2;
        } else {
            context.cbpState[slot] = 0;
        }
    }

    /**
     * Saturates a CBP model count to [-16, 15].
     */
    private static int saturate(int value) {
        if (value + 16 < 0 || value + 16 >= 32) {
            return value < 0 ? -16 : 15;
        }
        return value;
    }

    /**
     * Undoes the CBP prediction of a 16-block channel (predCBPCDec).
     */
    private int predictCbpChannel(int value, int x, int c, Context context) {
        var slot = c != 0 ? 1 : 0;
        if (context.cbpState[slot] == 0) {
            if (contextLeft) {
                if (contextTop) {
                    value ^= 1;
                } else {
                    value ^= (predPrevious[c][x].cbp >> 10) & 1;
                }
            } else {
                value ^= (pred[c][x - 1].cbp >> 5) & 1;
            }
            value ^= 0x02 & (value << 1);
            value ^= 0x10 & (value << 3);
            value ^= 0x20 & (value << 1);
            value ^= (value & 0x33) << 2;
            value ^= (value & 0xcc) << 6;
            value ^= (value & 0x3300) << 2;
        } else if (context.cbpState[slot] == 2) {
            value ^= 0xffff;
        }
        updateCbpModel(context, slot, ones(value));
        return value;
    }

    /**
     * Undoes the CBP prediction of a 4:2:0 or 4:2:2 chroma channel (predCBPC420Dec, predCBPC422Dec).
     *
     * @param topBit the bit of the macroblock above that predicts the first block
     * @param mask the mask of the channel's blocks
     * @param weight the number of 4x4 luma blocks each chroma block weighs
     */
    private int predictCbpChroma(int value, int x, int c, Context context, int topBit, int mask, int weight) {
        if (context.cbpState[1] == 0) {
            if (contextLeft) {
                if (contextTop) {
                    value ^= 1;
                } else {
                    value ^= (predPrevious[c][x].cbp >> topBit) & 1;
                }
            } else {
                value ^= (pred[c][x - 1].cbp >> 1) & 1;
            }
            if (mask == 0xf) {
                value ^= 0x02 & (value << 1);
                value ^= (value & 0x3) << 2;
            } else {
                value ^= (value & 0x1) << 1;
                value ^= (value & 0x3) << 2;
                value ^= (value & 0xc) << 2;
                value ^= (value & 0x30) << 2;
            }
        } else if (context.cbpState[1] == 2) {
            value ^= mask;
        }
        updateCbpModel(context, 1, ones(value) * weight);
        return value;
    }

    /**
     * {@return the highpass quantizer step of a channel for the overlap operators, 255 when the band is absent}
     */
    private int overlapQp(int channel, boolean hpAbsent) {
        if (hpAbsent) {
            return 255;
        }
        return quantizer(tiles[tileColumn].hp, channel, qIndexHp).qp;
    }

    /**
     * Inverse transforms what the current position completes, with the operators of codec subversion 0
     * (invTransformMacroblock).
     */
    private void transform() {
        var overlap = header.overlap;
        var cf = colorFormat;
        var left = column == 0;
        var right = column == mbWidth;
        var top = row == 0;
        var bottom = row == mbHeight;
        var topOrBottom = top || bottom;
        var leftOrRight = left || right;
        var bottomOrRight = bottom || right;
        var count = cf == JxrHeader.YUV_420 || cf == JxrHeader.YUV_422 ? 1 : channels;
        var hpAbsent = plane.subband == JxrHeader.SB_NO_HIGHPASS || plane.subband == JxrHeader.SB_DC_ONLY;
        for (var i = 0; i < count; i++) {
            var q = rows[i];
            var p0 = previous[i] + column * stride[i];
            var p1 = current[i] + column * stride[i];
            var hpQp = overlapQp(i, hpAbsent);
            if (!bottomOrRight) {
                JxrTransform.idct4x4Stage2(q, p1);
                if (plane.scaled) {
                    JxrTransform.normalize(q, p1, i != 0);
                }
            }
            if (overlap == 2) {
                if (leftOrRight && !topOrBottom) {
                    var j = left ? 0 : -128;
                    JxrTransform.post4(q, p0 + j + 32, p0 + j + 48, p1 + j, p1 + j + 16);
                    JxrTransform.post4(q, p0 + j + 96, p0 + j + 112, p1 + j + 64, p1 + j + 80);
                }
                if (!leftOrRight) {
                    if (topOrBottom) {
                        var p = top ? p1 : p0 + 32;
                        JxrTransform.post4(q, p - 128, p - 64, p, p + 64);
                        JxrTransform.post4(q, p - 112, p - 48, p + 16, p + 80);
                    } else {
                        JxrTransform.post4x4Stage2Split(q, p0, p1);
                    }
                }
            }
            if (!top) {
                for (var j = left ? 32 : -96; j < (right ? 32 : 160); j += 64) {
                    JxrTransform.idct4x4Stage1(q, p0 + j);
                    JxrTransform.idct4x4Stage1(q, p0 + j + 16);
                }
            }
            if (!bottom) {
                for (var j = left ? 0 : -128; j < (right ? 0 : 128); j += 64) {
                    JxrTransform.idct4x4Stage1(q, p1 + j);
                    JxrTransform.idct4x4Stage1(q, p1 + j + 16);
                }
            }
            if (overlap != 0) {
                if (leftOrRight) {
                    var j = left ? 10 : -64 + 14;
                    if (!top) {
                        var p = p0 + 16 + j;
                        JxrTransform.post4(q, p, p - 2, p + 6, p + 8);
                        JxrTransform.post4(q, p + 1, p - 1, p + 7, p + 9);
                        JxrTransform.post4(q, p + 16, p + 14, p + 22, p + 24);
                        JxrTransform.post4(q, p + 17, p + 15, p + 23, p + 25);
                    }
                    if (!bottom) {
                        var p = p1 + j;
                        JxrTransform.post4(q, p, p - 2, p + 6, p + 8);
                        JxrTransform.post4(q, p + 1, p - 1, p + 7, p + 9);
                    }
                    if (!topOrBottom) {
                        JxrTransform.post4(q, p0 + 48 + j, p0 + 48 + j - 2, p1 - 10 + j, p1 - 8 + j);
                        JxrTransform.post4(q, p0 + 48 + j + 1, p0 + 48 + j - 1, p1 - 9 + j, p1 - 7 + j);
                    }
                }
                if (top) {
                    for (var j = left ? 0 : -192; j < (right ? -64 : 64); j += 64) {
                        var p = p1 + j;
                        JxrTransform.post4(q, p + 5, p + 4, p + 64, p + 65);
                        JxrTransform.post4(q, p + 7, p + 6, p + 66, p + 67);
                        JxrTransform.post4x4Stage1(q, p1 + j, 0, hpQp, hpAbsent);
                    }
                } else if (bottom) {
                    for (var j = left ? 0 : -192; j < (right ? -64 : 64); j += 64) {
                        JxrTransform.post4x4Stage1(q, p0 + 16 + j, 0, hpQp, hpAbsent);
                        JxrTransform.post4x4Stage1(q, p0 + 32 + j, 0, hpQp, hpAbsent);
                        var p = p0 + 48 + j;
                        JxrTransform.post4(q, p + 15, p + 14, p + 74, p + 75);
                        JxrTransform.post4(q, p + 13, p + 12, p + 72, p + 73);
                    }
                } else {
                    for (var j = left ? 0 : -192; j < (right ? -64 : 64); j += 64) {
                        JxrTransform.post4x4Stage1(q, p0 + 16 + j, 0, hpQp, hpAbsent);
                        JxrTransform.post4x4Stage1(q, p0 + 32 + j, 0, hpQp, hpAbsent);
                        JxrTransform.post4x4Stage1Split(q, p0 + 48 + j, p1 + j, 0, hpQp, hpAbsent);
                        JxrTransform.post4x4Stage1(q, p1 + j, 0, hpQp, hpAbsent);
                    }
                }
            }
        }
        if (cf == JxrHeader.YUV_420) {
            for (var i = 0; i < 2; i++) {
                var q = rows[1 + i];
                var p0 = previous[1 + i] + column * stride[1 + i];
                var p1 = current[1 + i] + column * stride[1 + i];
                var hpQp = overlapQp(i, hpAbsent);
                if (!bottomOrRight) {
                    if (!plane.scaled) {
                        JxrTransform.dct2x2dn(q, p1, p1 + 32, p1 + 16, p1 + 48);
                    } else {
                        JxrTransform.dct2x2dnScaled(q, p1, p1 + 32, p1 + 16, p1 + 48);
                    }
                }
                if (overlap == 2) {
                    if (leftOrRight && !topOrBottom) {
                        var j = left ? 0 : -32;
                        JxrTransform.post2(q, p0 + j + 16, p1 + j);
                    }
                    if (!leftOrRight) {
                        if (topOrBottom) {
                            var p = top ? p1 : p0 + 16;
                            JxrTransform.post2(q, p - 32, p);
                        } else {
                            JxrTransform.post2x2(q, p0 - 16, p0 + 16, p1 - 32, p1);
                        }
                    }
                }
                if (!top) {
                    for (var j = left ? 16 : -16; j < (right ? 16 : 48); j += 32) {
                        JxrTransform.idct4x4Stage1(q, p0 + j);
                    }
                }
                if (!bottom) {
                    for (var j = left ? 0 : -32; j < (right ? 0 : 32); j += 32) {
                        JxrTransform.idct4x4Stage1(q, p1 + j);
                    }
                }
                if (overlap != 0) {
                    if (!left && !top) {
                        if (bottom) {
                            for (var j = -48; j < (right ? -16 : 16); j += 32) {
                                var p = p0 + j;
                                JxrTransform.post4(q, p + 15, p + 14, p + 42, p + 43);
                                JxrTransform.post4(q, p + 13, p + 12, p + 40, p + 41);
                            }
                        } else {
                            for (var j = -48; j < (right ? -16 : 16); j += 32) {
                                JxrTransform.post4x4Stage1Split(q, p0 + j, p1 - 16 + j, 32, hpQp, hpAbsent);
                            }
                        }
                        if (right) {
                            if (!bottom) {
                                JxrTransform.post4(q, p0 - 2, p0 - 4, p1 - 28, p1 - 26);
                                JxrTransform.post4(q, p0 - 1, p0 - 3, p1 - 27, p1 - 25);
                            }
                            JxrTransform.post4(q, p0 - 18, p0 - 20, p0 - 12, p0 - 10);
                            JxrTransform.post4(q, p0 - 17, p0 - 19, p0 - 11, p0 - 9);
                        } else {
                            JxrTransform.post4x4Stage1(q, p0 - 32, 32, hpQp, hpAbsent);
                        }
                        JxrTransform.post4x4Stage1(q, p0 - 64, 32, hpQp, hpAbsent);
                    } else if (top) {
                        for (var j = left ? 0 : -64; j < (right ? -32 : 0); j += 32) {
                            var p = p1 + j + 4;
                            JxrTransform.post4(q, p + 1, p, p + 28, p + 29);
                            JxrTransform.post4(q, p + 3, p + 2, p + 30, p + 31);
                        }
                    } else {
                        if (!bottom) {
                            JxrTransform.post4(q, p0 + 26, p0 + 24, p1, p1 + 2);
                            JxrTransform.post4(q, p0 + 27, p0 + 25, p1 + 1, p1 + 3);
                        }
                        JxrTransform.post4(q, p0 + 10, p0 + 8, p0 + 16, p0 + 18);
                        JxrTransform.post4(q, p0 + 11, p0 + 9, p0 + 17, p0 + 19);
                    }
                }
            }
        } else if (cf == JxrHeader.YUV_422) {
            for (var i = 0; i < 2; i++) {
                var q = rows[1 + i];
                var p0 = previous[1 + i] + column * stride[1 + i];
                var p1 = current[1 + i] + column * stride[1 + i];
                var hpQp = overlapQp(i, hpAbsent);
                if (!bottomOrRight) {
                    q[p1] -= (q[p1 + 32] + 1) >> 1;
                    q[p1 + 32] += q[p1];
                    if (!plane.scaled) {
                        JxrTransform.dct2x2dn(q, p1, p1 + 64, p1 + 16, p1 + 80);
                        JxrTransform.dct2x2dn(q, p1 + 32, p1 + 96, p1 + 48, p1 + 112);
                    } else {
                        JxrTransform.dct2x2dnScaled(q, p1, p1 + 64, p1 + 16, p1 + 80);
                        JxrTransform.dct2x2dnScaled(q, p1 + 32, p1 + 96, p1 + 48, p1 + 112);
                    }
                }
                if (overlap == 2) {
                    if (!bottom) {
                        if (leftOrRight) {
                            if (!top) {
                                var j = left ? 0 : -64;
                                JxrTransform.post2(q, p0 + 48 + j, p1 + j);
                            }
                            var j = left ? 16 : -48;
                            JxrTransform.post2(q, p1 + j, p1 + j + 16);
                        } else {
                            if (top) {
                                JxrTransform.post2(q, p1 - 64, p1);
                            } else {
                                JxrTransform.post2x2(q, p0 - 16, p0 + 48, p1 - 64, p1);
                            }
                            JxrTransform.post2x2(q, p1 - 48, p1 + 16, p1 - 32, p1 + 32);
                        }
                    } else if (!leftOrRight) {
                        JxrTransform.post2(q, p0 - 16, p0 + 48);
                    }
                }
                if (!top) {
                    for (var j = left ? 48 : -16; j < (right ? 48 : 112); j += 64) {
                        JxrTransform.idct4x4Stage1(q, p0 + j);
                    }
                }
                if (!bottom) {
                    for (var j = left ? 0 : -64; j < (right ? 0 : 64); j += 64) {
                        JxrTransform.idct4x4Stage1(q, p1 + j);
                        JxrTransform.idct4x4Stage1(q, p1 + j + 16);
                        JxrTransform.idct4x4Stage1(q, p1 + j + 32);
                    }
                }
                if (overlap != 0) {
                    if (!top) {
                        if (leftOrRight) {
                            var p = p0 + (left ? 32 + 10 : -32 + 14);
                            JxrTransform.post4(q, p, p - 2, p + 6, p + 8);
                            JxrTransform.post4(q, p + 1, p - 1, p + 7, p + 9);
                        }
                        for (var j = left ? 0 : -128; j < (right ? -64 : 0); j += 64) {
                            JxrTransform.post4x4Stage1(q, p0 + j + 32, 0, hpQp, hpAbsent);
                        }
                    }
                    if (!bottom) {
                        if (leftOrRight) {
                            var p = p1 + (left ? 10 : -64 + 14);
                            JxrTransform.post4(q, p, p - 2, p + 6, p + 8);
                            JxrTransform.post4(q, p + 1, p - 1, p + 7, p + 9);
                            p += 16;
                            JxrTransform.post4(q, p, p - 2, p + 6, p + 8);
                            JxrTransform.post4(q, p + 1, p - 1, p + 7, p + 9);
                        }
                        for (var j = left ? 0 : -128; j < (right ? -64 : 0); j += 64) {
                            JxrTransform.post4x4Stage1(q, p1 + j, 0, hpQp, hpAbsent);
                            JxrTransform.post4x4Stage1(q, p1 + j + 16, 0, hpQp, hpAbsent);
                        }
                    }
                    if (topOrBottom) {
                        var p = top ? p1 + 5 : p0 + 48 + 13;
                        for (var j = left ? 0 : -128; j < (right ? -64 : 0); j += 64) {
                            JxrTransform.post4(q, p + j, p + j - 1, p + j + 59, p + j + 60);
                            JxrTransform.post4(q, p + j + 2, p + j + 1, p + j + 61, p + j + 62);
                        }
                    } else {
                        if (leftOrRight) {
                            var j = left ? 0 : -64 + 4;
                            JxrTransform.post4(q, p0 + j + 58, p0 + j + 56, p1 + j, p1 + j + 2);
                            JxrTransform.post4(q, p0 + j + 59, p0 + j + 57, p1 + j + 1, p1 + j + 3);
                        }
                        for (var j = left ? 0 : -128; j < (right ? -64 : 0); j += 64) {
                            JxrTransform.post4x4Stage1Split(q, p0 + j + 48, p1 + j, 0, hpQp, hpAbsent);
                        }
                    }
                }
            }
        }
    }

    /**
     * Inverse transforms what the current position completes, with the rescaled operators of codec subversions 1
     * and 9 and the hard tile boundaries of subversion 9 (invTransformMacroblock_alteredOperators_hard).
     *
     * @implNote This implementation finds the vertical tile boundaries from the tile rows and the horizontal ones
     * from the tile columns, as jxrlib does, so that it decodes the files jxrlib writes as jxrlib decodes them.
     */
    private void transformAlternate() {
        var overlap = header.overlap;
        var cf = colorFormat;
        var left = column == 0;
        var right = column == mbWidth;
        var top = row == 0;
        var bottom = row == mbHeight;
        var topOrBottom = top || bottom;
        var leftOrRight = left || right;
        var bottomOrRight = bottom || right;
        var leftAdjacent = column == 1;
        var rightAdjacent = column == mbWidth - 1;
        var count = cf == JxrHeader.YUV_420 || cf == JxrHeader.YUV_422 ? 1 : channels;
        if (header.hardTiles) {
            if (column == 0) {
                verticalTileBoundary = false;
                hardTileY = 0;
            }
            oneLeftOfVerticalBoundary = oneRightOfVerticalBoundary = false;
            if (hardTileY > 0 && hardTileY <= header.tileRowsMinus1 && column - 1 == header.tileY[hardTileY]) {
                oneRightOfVerticalBoundary = true;
            }
            if (hardTileY < header.tileRowsMinus1 && column == header.tileY[hardTileY + 1]) {
                verticalTileBoundary = true;
                hardTileY++;
            } else {
                verticalTileBoundary = false;
            }
            if (hardTileY < header.tileRowsMinus1 && column + 1 == header.tileY[hardTileY + 1]) {
                oneLeftOfVerticalBoundary = true;
            }
            if (row == 0) {
                horizontalTileBoundary = false;
                hardTileX = 0;
            } else if (mbY != row && hardTileX < header.tileColumnsMinus1 && row == header.tileX[hardTileX + 1]) {
                horizontalTileBoundary = true;
                hardTileX++;
            } else if (mbY != row) {
                horizontalTileBoundary = false;
            }
        } else {
            verticalTileBoundary = false;
            horizontalTileBoundary = false;
            oneLeftOfVerticalBoundary = false;
            oneRightOfVerticalBoundary = false;
        }
        mbX = column;
        mbY = row;
        var vtb = verticalTileBoundary;
        var htb = horizontalTileBoundary;
        var oneLeft = oneLeftOfVerticalBoundary;
        var oneRight = oneRightOfVerticalBoundary;
        for (var i = 0; i < count; i++) {
            var q = rows[i];
            var p0 = previous[i] + column * stride[i];
            var p1 = current[i] + column * stride[i];
            if (!bottomOrRight) {
                JxrTransform.idct4x4Stage2(q, p1);
                if (plane.scaled) {
                    JxrTransform.normalize(q, p1, i != 0);
                }
            }
            if (overlap == 2) {
                if ((top || htb) && (left || vtb)) {
                    JxrTransform.post4Alternate(q, p1, p1 + 64, p1 + 16, p1 + 64 + 16);
                }
                if ((top || htb) && (right || vtb)) {
                    JxrTransform.post4Alternate(q, p1 - 128, p1 - 64, p1 - 128 + 16, p1 - 64 + 16);
                }
                if ((bottom || htb) && (left || vtb)) {
                    JxrTransform.post4Alternate(q, p0 + 32, p0 + 96, p0 + 32 + 16, p0 + 96 + 16);
                }
                if ((bottom || htb) && (right || vtb)) {
                    JxrTransform.post4Alternate(q, p0 - 96, p0 - 32, p0 - 96 + 16, p0 - 32 + 16);
                }
                if ((leftOrRight || vtb) && !topOrBottom && !htb) {
                    if (left || vtb) {
                        JxrTransform.post4Alternate(q, p0 + 32, p0 + 48, p1, p1 + 16);
                        JxrTransform.post4Alternate(q, p0 + 96, p0 + 112, p1 + 64, p1 + 80);
                    }
                    if (right || vtb) {
                        var j = -128;
                        JxrTransform.post4Alternate(q, p0 + j + 32, p0 + j + 48, p1 + j, p1 + j + 16);
                        JxrTransform.post4Alternate(q, p0 + j + 96, p0 + j + 112, p1 + j + 64, p1 + j + 80);
                    }
                }
                if (!leftOrRight) {
                    if ((topOrBottom || htb) && !vtb) {
                        if (top || htb) {
                            var p = p1;
                            JxrTransform.post4Alternate(q, p - 128, p - 64, p, p + 64);
                            JxrTransform.post4Alternate(q, p - 112, p - 48, p + 16, p + 80);
                        }
                        if (bottom || htb) {
                            var p = p0 + 32;
                            JxrTransform.post4Alternate(q, p - 128, p - 64, p, p + 64);
                            JxrTransform.post4Alternate(q, p - 112, p - 48, p + 16, p + 80);
                        }
                    }
                    if (!topOrBottom && !htb && !vtb) {
                        JxrTransform.post4x4Stage2SplitAlternate(q, p0, p1);
                    }
                }
            }
            if (!top) {
                for (var j = left ? 32 : -96; j < (right ? 32 : 160); j += 64) {
                    JxrTransform.idct4x4Stage1(q, p0 + j);
                    JxrTransform.idct4x4Stage1(q, p0 + j + 16);
                }
            }
            if (!bottom) {
                for (var j = left ? 0 : -128; j < (right ? 0 : 128); j += 64) {
                    JxrTransform.idct4x4Stage1(q, p1 + j);
                    JxrTransform.idct4x4Stage1(q, p1 + j + 16);
                }
            }
            if (overlap != 0) {
                if (leftOrRight || vtb) {
                    if ((top || htb) && (left || vtb)) {
                        JxrTransform.post4Alternate(q, p1, p1 + 1, p1 + 2, p1 + 3);
                    }
                    if ((top || htb) && (right || vtb)) {
                        JxrTransform.post4Alternate(q, p1 - 59, p1 - 60, p1 - 57, p1 - 58);
                    }
                    if ((bottom || htb) && (left || vtb)) {
                        JxrTransform.post4Alternate(q, p0 + 58, p0 + 59, p0 + 56, p0 + 57);
                    }
                    if ((bottom || htb) && (right || vtb)) {
                        JxrTransform.post4Alternate(q, p0 - 1, p0 - 2, p0 - 3, p0 - 4);
                    }
                    if (left || vtb) {
                        edgeFilters(q, p0, p1, 10, top, bottom, topOrBottom || htb);
                    }
                    if (right || vtb) {
                        edgeFilters(q, p0, p1, -64 + 14, top, bottom, topOrBottom || htb);
                    }
                }
                if (top || htb) {
                    for (var j = left ? 0 : -192; j < (right ? -64 : 64); j += 64) {
                        if (!vtb || j != -64) {
                            var p = p1 + j;
                            JxrTransform.post4Alternate(q, p + 5, p + 4, p + 64, p + 65);
                            JxrTransform.post4Alternate(q, p + 7, p + 6, p + 66, p + 67);
                            JxrTransform.post4x4Stage1Alternate(q, p1 + j, 0);
                        }
                    }
                }
                if (bottom || htb) {
                    for (var j = left ? 0 : -192; j < (right ? -64 : 64); j += 64) {
                        if (!vtb || j != -64) {
                            JxrTransform.post4x4Stage1Alternate(q, p0 + 16 + j, 0);
                            JxrTransform.post4x4Stage1Alternate(q, p0 + 32 + j, 0);
                            var p = p0 + 48 + j;
                            JxrTransform.post4Alternate(q, p + 15, p + 14, p + 74, p + 75);
                            JxrTransform.post4Alternate(q, p + 13, p + 12, p + 72, p + 73);
                        }
                    }
                }
                if (!top && !bottom && !htb) {
                    for (var j = left ? 0 : -192; j < (right ? -64 : 64); j += 64) {
                        if (!vtb || j != -64) {
                            JxrTransform.post4x4Stage1Alternate(q, p0 + 16 + j, 0);
                            JxrTransform.post4x4Stage1Alternate(q, p0 + 32 + j, 0);
                            JxrTransform.post4x4Stage1SplitAlternate(q, p0 + 48 + j, p1 + j, 0);
                            JxrTransform.post4x4Stage1Alternate(q, p1 + j, 0);
                        }
                    }
                }
            }
        }
        if (cf == JxrHeader.YUV_420) {
            for (var i = 0; i < 2; i++) {
                var q = rows[1 + i];
                var p0 = previous[1 + i] + column * stride[1 + i];
                var p1 = current[1 + i] + column * stride[1 + i];
                if (!bottomOrRight) {
                    if (!plane.scaled) {
                        JxrTransform.dct2x2dn(q, p1, p1 + 32, p1 + 16, p1 + 48);
                    } else {
                        JxrTransform.dct2x2dnScaled(q, p1, p1 + 32, p1 + 16, p1 + 48);
                    }
                }
                if (overlap == 2) {
                    if ((leftAdjacent || oneRight) && (top || htb)) {
                        q[p1 - 64] -= q[p1 - 64 + 32];
                    }
                    if ((rightAdjacent || oneLeft) && (top || htb)) {
                        predBefore[i][0] = q[p1];
                    }
                    if ((right || vtb) && (top || htb)) {
                        q[p1 - 64 + 32] -= predBefore[i][0];
                    }
                    if ((leftAdjacent || oneRight) && (bottom || htb)) {
                        q[p0 - 64 + 16] -= q[p0 - 64 + 48];
                    }
                    if ((rightAdjacent || oneLeft) && (bottom || htb)) {
                        predBefore[i][1] = q[p0 + 16];
                    }
                    if ((right || vtb) && (bottom || htb)) {
                        q[p0 - 64 + 48] -= predBefore[i][1];
                    }
                    if ((leftOrRight || vtb) && !topOrBottom && !htb) {
                        if (left || vtb) {
                            JxrTransform.post2Alternate(q, p0 + 16, p1);
                        }
                        if (right || vtb) {
                            JxrTransform.post2Alternate(q, p0 - 32 + 16, p1 - 32);
                        }
                    }
                    if (!leftOrRight) {
                        if ((topOrBottom || htb) && !vtb) {
                            if (top || htb) {
                                JxrTransform.post2Alternate(q, p1 - 32, p1);
                            }
                            if (bottom || htb) {
                                JxrTransform.post2Alternate(q, p0 + 16 - 32, p0 + 16);
                            }
                        } else if (!topOrBottom && !htb && !vtb) {
                            JxrTransform.post2x2Alternate(q, p0 - 16, p0 + 16, p1 - 32, p1);
                        }
                    }
                    if ((leftAdjacent || oneRight) && (top || htb)) {
                        q[p1 - 64] += q[p1 - 64 + 32];
                    }
                    if ((rightAdjacent || oneLeft) && (top || htb)) {
                        predAfter[i][0] = q[p1];
                    }
                    if ((right || vtb) && (top || htb)) {
                        q[p1 - 64 + 32] += predAfter[i][0];
                    }
                    if ((leftAdjacent || oneRight) && (bottom || htb)) {
                        q[p0 - 64 + 16] += q[p0 - 64 + 48];
                    }
                    if ((rightAdjacent || oneLeft) && (bottom || htb)) {
                        predAfter[i][1] = q[p0 + 16];
                    }
                    if ((right || vtb) && (bottom || htb)) {
                        q[p0 - 64 + 48] += predAfter[i][1];
                    }
                }
                if (!top) {
                    for (var j = left ? 48 : (leftAdjacent || oneRight) ? -48 : -16; j < (right || vtb ? 16 : 48);
                         j += 32) {
                        JxrTransform.idct4x4Stage1(q, p0 + j);
                    }
                }
                if (!bottom) {
                    for (var j = left ? 32 : (leftAdjacent || oneRight) ? -64 : -32; j < (right || vtb ? 0 : 32);
                         j += 32) {
                        JxrTransform.idct4x4Stage1(q, p1 + j);
                    }
                }
                if (overlap != 0) {
                    if ((top || htb) && (leftAdjacent || oneRight)) {
                        JxrTransform.post4Alternate(q, p1 - 64, p1 - 64 + 1, p1 - 64 + 2, p1 - 64 + 3);
                    }
                    if ((top || htb) && (right || vtb)) {
                        JxrTransform.post4Alternate(q, p1 - 27, p1 - 28, p1 - 25, p1 - 26);
                    }
                    if ((bottom || htb) && (leftAdjacent || oneRight)) {
                        JxrTransform.post4Alternate(q, p0 - 64 + 26, p0 - 64 + 27, p0 - 64 + 24, p0 - 64 + 25);
                    }
                    if ((bottom || htb) && (right || vtb)) {
                        JxrTransform.post4Alternate(q, p0 - 1, p0 - 2, p0 - 3, p0 - 4);
                    }
                    if (!left && !top) {
                        if (leftAdjacent || oneRight) {
                            if (!bottom && !htb) {
                                JxrTransform.post4Alternate(q, p0 - 64 + 26, p0 - 64 + 24, p1 - 64, p1 - 64 + 2);
                                JxrTransform.post4Alternate(q, p0 - 64 + 27, p0 - 64 + 25, p1 - 64 + 1,
                                        p1 - 64 + 3);
                            }
                            JxrTransform.post4Alternate(q, p0 - 64 + 10, p0 - 64 + 8, p0 - 64 + 16, p0 - 64 + 18);
                            JxrTransform.post4Alternate(q, p0 - 64 + 11, p0 - 64 + 9, p0 - 64 + 17, p0 - 64 + 19);
                        }
                        if (bottom || htb) {
                            var p = p0 - 48;
                            JxrTransform.post4Alternate(q, p + 15, p + 14, p + 42, p + 43);
                            JxrTransform.post4Alternate(q, p + 13, p + 12, p + 40, p + 41);
                            if (!right && !vtb) {
                                p = p0 - 16;
                                JxrTransform.post4Alternate(q, p + 15, p + 14, p + 42, p + 43);
                                JxrTransform.post4Alternate(q, p + 13, p + 12, p + 40, p + 41);
                            }
                        } else {
                            JxrTransform.post4x4Stage1SplitAlternate(q, p0 - 48, p1 - 16 - 48, 32);
                            if (!right && !vtb) {
                                JxrTransform.post4x4Stage1SplitAlternate(q, p0 - 16, p1 - 16 - 16, 32);
                            }
                        }
                        if (right || vtb) {
                            if (!bottom && !htb) {
                                JxrTransform.post4Alternate(q, p0 - 2, p0 - 4, p1 - 28, p1 - 26);
                                JxrTransform.post4Alternate(q, p0 - 1, p0 - 3, p1 - 27, p1 - 25);
                            }
                            JxrTransform.post4Alternate(q, p0 - 18, p0 - 20, p0 - 12, p0 - 10);
                            JxrTransform.post4Alternate(q, p0 - 17, p0 - 19, p0 - 11, p0 - 9);
                        } else {
                            JxrTransform.post4x4Stage1Alternate(q, p0 - 32, 32);
                        }
                        JxrTransform.post4x4Stage1Alternate(q, p0 - 64, 32);
                    }
                    if (top || htb) {
                        if (!left) {
                            var p = p1 - 64 + 4;
                            JxrTransform.post4Alternate(q, p + 1, p, p + 28, p + 29);
                            JxrTransform.post4Alternate(q, p + 3, p + 2, p + 30, p + 31);
                        }
                        if (!left && !right && !vtb) {
                            var p = p1 - 32 + 4;
                            JxrTransform.post4Alternate(q, p + 1, p, p + 28, p + 29);
                            JxrTransform.post4Alternate(q, p + 3, p + 2, p + 30, p + 31);
                        }
                    }
                }
            }
        } else if (cf == JxrHeader.YUV_422) {
            for (var i = 0; i < 2; i++) {
                var q = rows[1 + i];
                var p0 = previous[1 + i] + column * stride[1 + i];
                var p1 = current[1 + i] + column * stride[1 + i];
                if (!bottomOrRight) {
                    q[p1] -= (q[p1 + 32] + 1) >> 1;
                    q[p1 + 32] += q[p1];
                    if (!plane.scaled) {
                        JxrTransform.dct2x2dn(q, p1, p1 + 64, p1 + 16, p1 + 80);
                        JxrTransform.dct2x2dn(q, p1 + 32, p1 + 96, p1 + 48, p1 + 112);
                    } else {
                        JxrTransform.dct2x2dnScaled(q, p1, p1 + 64, p1 + 16, p1 + 80);
                        JxrTransform.dct2x2dnScaled(q, p1 + 32, p1 + 96, p1 + 48, p1 + 112);
                    }
                }
                if (overlap == 2) {
                    if ((leftAdjacent || oneRight) && (top || htb)) {
                        q[p1 - 128] -= q[p1 - 128 + 64];
                    }
                    if ((rightAdjacent || oneLeft) && (top || htb)) {
                        predBefore[i][0] = q[p1];
                    }
                    if ((right || vtb) && (top || htb)) {
                        q[p1 - 128 + 64] -= predBefore[i][0];
                    }
                    if ((leftAdjacent || oneRight) && (bottom || htb)) {
                        q[p0 - 128 + 48] -= q[p0 - 128 + 112];
                    }
                    if ((rightAdjacent || oneLeft) && (bottom || htb)) {
                        predBefore[i][1] = q[p0 + 48];
                    }
                    if ((right || vtb) && (bottom || htb)) {
                        q[p0 - 128 + 112] -= predBefore[i][1];
                    }
                    if (!bottom) {
                        if (leftOrRight || vtb) {
                            if (!top && !htb) {
                                if (left || vtb) {
                                    JxrTransform.post2Alternate(q, p0 + 48, p1);
                                }
                                if (right || vtb) {
                                    JxrTransform.post2Alternate(q, p0 + 48 - 64, p1 - 64);
                                }
                            }
                            if (left || vtb) {
                                JxrTransform.post2Alternate(q, p1 + 16, p1 + 32);
                            }
                            if (right || vtb) {
                                JxrTransform.post2Alternate(q, p1 - 48, p1 - 32);
                            }
                        }
                        if (!leftOrRight && !vtb) {
                            if (top || htb) {
                                JxrTransform.post2Alternate(q, p1 - 64, p1);
                            } else {
                                JxrTransform.post2x2Alternate(q, p0 - 16, p0 + 48, p1 - 64, p1);
                            }
                            JxrTransform.post2x2Alternate(q, p1 - 48, p1 + 16, p1 - 32, p1 + 32);
                        }
                    }
                    if ((bottom || htb) && !leftOrRight && !vtb) {
                        JxrTransform.post2Alternate(q, p0 - 16, p0 + 48);
                    }
                    if ((leftAdjacent || oneRight) && (top || htb)) {
                        q[p1 - 128] += q[p1 - 128 + 64];
                    }
                    if ((rightAdjacent || oneLeft) && (top || htb)) {
                        predAfter[i][0] = q[p1];
                    }
                    if ((right || vtb) && (top || htb)) {
                        q[p1 - 128 + 64] += predAfter[i][0];
                    }
                    if ((leftAdjacent || oneRight) && (bottom || htb)) {
                        q[p0 - 128 + 48] += q[p0 - 128 + 112];
                    }
                    if ((rightAdjacent || oneLeft) && (bottom || htb)) {
                        predAfter[i][1] = q[p0 + 48];
                    }
                    if ((right || vtb) && (bottom || htb)) {
                        q[p0 - 128 + 112] += predAfter[i][1];
                    }
                }
                if (!top) {
                    for (var j = left ? 112 : (leftAdjacent || oneRight) ? -80 : -16; j < (right || vtb ? 48 : 112);
                         j += 64) {
                        JxrTransform.idct4x4Stage1(q, p0 + j);
                    }
                }
                if (!bottom) {
                    for (var j = left ? 64 : (leftAdjacent || oneRight) ? -128 : -64; j < (right || vtb ? 0 : 64);
                         j += 64) {
                        JxrTransform.idct4x4Stage1(q, p1 + j);
                        JxrTransform.idct4x4Stage1(q, p1 + j + 16);
                        JxrTransform.idct4x4Stage1(q, p1 + j + 32);
                    }
                }
                if (overlap != 0) {
                    if ((top || htb) && (leftAdjacent || oneRight)) {
                        JxrTransform.post4Alternate(q, p1 - 128, p1 - 128 + 1, p1 - 128 + 2, p1 - 128 + 3);
                    }
                    if ((top || htb) && (right || vtb)) {
                        JxrTransform.post4Alternate(q, p1 - 59, p1 - 60, p1 - 57, p1 - 58);
                    }
                    if ((bottom || htb) && (leftAdjacent || oneRight)) {
                        JxrTransform.post4Alternate(q, p0 - 128 + 58, p0 - 128 + 59, p0 - 128 + 56, p0 - 128 + 57);
                    }
                    if ((bottom || htb) && (right || vtb)) {
                        JxrTransform.post4Alternate(q, p0 - 1, p0 - 2, p0 - 3, p0 - 4);
                    }
                    if (!top) {
                        if (leftAdjacent || oneRight) {
                            var p = p0 + 32 + 10 - 128;
                            JxrTransform.post4Alternate(q, p, p - 2, p + 6, p + 8);
                            JxrTransform.post4Alternate(q, p + 1, p - 1, p + 7, p + 9);
                        }
                        if (right || vtb) {
                            var p = p0 - 32 + 14;
                            JxrTransform.post4Alternate(q, p, p - 2, p + 6, p + 8);
                            JxrTransform.post4Alternate(q, p + 1, p - 1, p + 7, p + 9);
                        }
                        for (var j = left ? 0 : -128; j < (right || vtb ? -64 : 0); j += 64) {
                            JxrTransform.post4x4Stage1Alternate(q, p0 + j + 32, 0);
                        }
                    }
                    if (!bottom) {
                        if (leftAdjacent || oneRight) {
                            var p = p1 + 10 - 128;
                            JxrTransform.post4Alternate(q, p, p - 2, p + 6, p + 8);
                            JxrTransform.post4Alternate(q, p + 1, p - 1, p + 7, p + 9);
                            p += 16;
                            JxrTransform.post4Alternate(q, p, p - 2, p + 6, p + 8);
                            JxrTransform.post4Alternate(q, p + 1, p - 1, p + 7, p + 9);
                        }
                        if (right || vtb) {
                            var p = p1 - 64 + 14;
                            JxrTransform.post4Alternate(q, p, p - 2, p + 6, p + 8);
                            JxrTransform.post4Alternate(q, p + 1, p - 1, p + 7, p + 9);
                            p += 16;
                            JxrTransform.post4Alternate(q, p, p - 2, p + 6, p + 8);
                            JxrTransform.post4Alternate(q, p + 1, p - 1, p + 7, p + 9);
                        }
                        for (var j = left ? 0 : -128; j < (right || vtb ? -64 : 0); j += 64) {
                            JxrTransform.post4x4Stage1Alternate(q, p1 + j, 0);
                            JxrTransform.post4x4Stage1Alternate(q, p1 + j + 16, 0);
                        }
                    }
                    if (topOrBottom || htb) {
                        if (top || htb) {
                            var p = p1 + 5;
                            for (var j = left ? 0 : -128; j < (right || vtb ? -64 : 0); j += 64) {
                                JxrTransform.post4Alternate(q, p + j, p + j - 1, p + j + 59, p + j + 60);
                                JxrTransform.post4Alternate(q, p + j + 2, p + j + 1, p + j + 61, p + j + 62);
                            }
                        }
                        if (bottom || htb) {
                            var p = p0 + 48 + 13;
                            for (var j = left ? 0 : -128; j < (right || vtb ? -64 : 0); j += 64) {
                                JxrTransform.post4Alternate(q, p + j, p + j - 1, p + j + 59, p + j + 60);
                                JxrTransform.post4Alternate(q, p + j + 2, p + j + 1, p + j + 61, p + j + 62);
                            }
                        }
                    } else {
                        if (leftAdjacent || oneRight) {
                            var j = -128;
                            JxrTransform.post4Alternate(q, p0 + j + 58, p0 + j + 56, p1 + j, p1 + j + 2);
                            JxrTransform.post4Alternate(q, p0 + j + 59, p0 + j + 57, p1 + j + 1, p1 + j + 3);
                        }
                        if (right || vtb) {
                            var j = -64 + 4;
                            JxrTransform.post4Alternate(q, p0 + j + 58, p0 + j + 56, p1 + j, p1 + j + 2);
                            JxrTransform.post4Alternate(q, p0 + j + 59, p0 + j + 57, p1 + j + 1, p1 + j + 3);
                        }
                        for (var j = left ? 0 : -128; j < (right || vtb ? -64 : 0); j += 64) {
                            JxrTransform.post4x4Stage1SplitAlternate(q, p0 + j + 48, p1 + j, 0);
                        }
                    }
                }
            }
        }
    }

    /**
     * Applies the vertical first-stage overlap operators of a left or right edge of the luma-sized blocks.
     *
     * @param j the offset of the edge column from the macroblock
     * @param interiorBlocked whether the operator across the macroblock rows is skipped
     */
    private static void edgeFilters(int[] q, int p0, int p1, int j, boolean top, boolean bottom,
                                    boolean interiorBlocked) {
        if (!top) {
            var p = p0 + 16 + j;
            JxrTransform.post4Alternate(q, p, p - 2, p + 6, p + 8);
            JxrTransform.post4Alternate(q, p + 1, p - 1, p + 7, p + 9);
            JxrTransform.post4Alternate(q, p + 16, p + 14, p + 22, p + 24);
            JxrTransform.post4Alternate(q, p + 17, p + 15, p + 23, p + 25);
        }
        if (!bottom) {
            var p = p1 + j;
            JxrTransform.post4Alternate(q, p, p - 2, p + 6, p + 8);
            JxrTransform.post4Alternate(q, p + 1, p - 1, p + 7, p + 9);
        }
        if (!interiorBlocked) {
            JxrTransform.post4Alternate(q, p0 + 48 + j, p0 + 48 + j - 2, p1 - 10 + j, p1 - 8 + j);
            JxrTransform.post4Alternate(q, p0 + 48 + j + 1, p0 + 48 + j - 1, p1 - 9 + j, p1 - 7 + j);
        }
    }
}
