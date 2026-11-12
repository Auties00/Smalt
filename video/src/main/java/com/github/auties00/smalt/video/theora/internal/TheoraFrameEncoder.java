/* Ports libtheora 1.2.0 (the VP3 quantization parameters of lib/encode.c, the DC prediction and the motion vector
   prediction of lib/decode.c). Copyright (C) 2002-2009 Xiph.org Foundation; BSD 3-Clause License. Modified for
   Smalt. */
package com.github.auties00.smalt.video.theora.internal;

import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;

import static com.github.auties00.smalt.video.theora.internal.TheoraFrameDecoder.FRAME_FOR_MODE;
import static com.github.auties00.smalt.video.theora.internal.TheoraFrameDecoder.FRAME_GOLD;
import static com.github.auties00.smalt.video.theora.internal.TheoraFrameDecoder.FRAME_NONE;
import static com.github.auties00.smalt.video.theora.internal.TheoraFrameDecoder.FRAME_SELF;
import static com.github.auties00.smalt.video.theora.internal.TheoraFrameDecoder.GOLDEN_MV;
import static com.github.auties00.smalt.video.theora.internal.TheoraFrameDecoder.GOLDEN_NOMV;
import static com.github.auties00.smalt.video.theora.internal.TheoraFrameDecoder.INTER_MV;
import static com.github.auties00.smalt.video.theora.internal.TheoraFrameDecoder.INTER_MV_FOUR;
import static com.github.auties00.smalt.video.theora.internal.TheoraFrameDecoder.INTER_MV_LAST;
import static com.github.auties00.smalt.video.theora.internal.TheoraFrameDecoder.INTER_MV_LAST2;
import static com.github.auties00.smalt.video.theora.internal.TheoraFrameDecoder.INTER_NOMV;
import static com.github.auties00.smalt.video.theora.internal.TheoraFrameDecoder.INTRA;
import static com.github.auties00.smalt.video.theora.internal.TheoraFrameDecoder.MB_MAP_IDXS;
import static com.github.auties00.smalt.video.theora.internal.TheoraFrameDecoder.MODE_ALPHABETS;
import static com.github.auties00.smalt.video.theora.internal.TheoraFrameDecoder.MVMAP;
import static com.github.auties00.smalt.video.theora.internal.TheoraFrameDecoder.MVMAP2;

/**
 * Encodes pictures as Theora key frames and inter frames, with the VP3 quantization parameters of libtheora and one
 * static Huffman code for every token table.
 *
 * <p>Every frame is coded at one quality index. A key frame codes every 8x8 block from its own samples; an inter
 * frame chooses for each macro block the mode of least estimated cost, the sum of the absolute luma prediction
 * errors plus a rate term: a block copied from the previous frame, the last or second to last motion vector, a new
 * half-sample motion vector found by a predictive search (and by a grid over the vector range where that fails),
 * one vector for each luma block, the golden frame (the last key frame) with or without a vector, or intra coding.
 * Blocks predicted from the previous frame without a vector are not coded when their residual quantizes to zero, or
 * when it is small and its coefficients reduce the sum of absolute errors by less than the estimated cost of their
 * tokens. The samples of every coded block, or their prediction error, go through an orthonormal forward DCT, are
 * quantized with the dequantization tables the setup header defines, DC-predicted as the decoder predicts them and
 * tokenized coefficient by coefficient in the coded order of the fragments, with end-of-block runs across blocks.
 * The picture is placed at the top left of a coded frame of whole macro blocks, the samples beyond it repeating the
 * last column and row.
 *
 * <p>A frame that codes no block is an empty packet. A key frame starts the stream, follows a
 * {@linkplain #requestKeyframe() request}, comes once the distance from the last key frame reaches the key frame
 * interval, and replaces an inter frame in which more than half of the macro blocks would be intra coded. The
 * predictions come from a {@link TheoraFrameDecoder} fed with every packet, so they equal the reconstruction of any
 * conforming decoder.
 *
 * <p>Instances are not thread-safe.
 */
public final class TheoraFrameEncoder {
    // TODO: Huffman codes fitted to each stream, rate control and rate-distortion optimized mode, skip and
    // coefficient decisions.
    private static final int[] DC_SCALE = {
            220, 200, 190, 180, 170, 170, 160, 160, 150, 150, 140, 140, 130, 130, 120, 120,
            110, 110, 100, 100, 90, 90, 90, 80, 80, 80, 70, 70, 70, 60, 60, 60,
            60, 50, 50, 50, 50, 40, 40, 40, 40, 40, 30, 30, 30, 30, 30, 30,
            30, 20, 20, 20, 20, 20, 20, 20, 20, 10, 10, 10, 10, 10, 10, 10};

    private static final int[] AC_SCALE = {
            500, 450, 400, 370, 340, 310, 285, 265, 245, 225, 210, 195, 185, 180, 170, 160,
            150, 145, 135, 130, 125, 115, 110, 107, 100, 96, 93, 89, 85, 82, 75, 74,
            70, 68, 64, 60, 57, 56, 52, 50, 49, 45, 44, 43, 40, 38, 37, 35,
            33, 32, 30, 29, 28, 25, 24, 22, 21, 19, 18, 17, 15, 13, 12, 10};

    private static final int[] LOOP_FILTER_LIMITS = {
            30, 25, 20, 20, 15, 15, 14, 14, 13, 13, 12, 12, 11, 11, 10, 10,
            9, 9, 8, 8, 7, 7, 7, 7, 6, 6, 6, 6, 5, 5, 5, 5,
            4, 4, 4, 4, 3, 3, 3, 3, 2, 2, 2, 2, 2, 2, 2, 2,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};

    private static final int[][] BASES = {
            {16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55,
                    14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
                    18, 22, 37, 58, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92,
                    49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99},
            {17, 18, 24, 47, 99, 99, 99, 99, 18, 21, 26, 66, 99, 99, 99, 99,
                    24, 26, 56, 99, 99, 99, 99, 99, 47, 66, 99, 99, 99, 99, 99, 99,
                    99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99,
                    99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99},
            {16, 16, 16, 20, 24, 28, 32, 40, 16, 16, 20, 24, 28, 32, 40, 48,
                    16, 20, 24, 28, 32, 40, 48, 64, 20, 24, 28, 32, 40, 48, 64, 64,
                    24, 28, 32, 40, 48, 64, 64, 64, 28, 32, 40, 48, 64, 64, 64, 96,
                    32, 40, 48, 64, 64, 64, 96, 128, 40, 48, 64, 64, 64, 96, 128, 128}};

    /**
     * The relative frequencies of the 32 tokens the static Huffman code is built from: end-of-block runs, zero
     * runs, values and zero runs followed by values, in token order.
     */
    private static final int[] TOKEN_FREQUENCIES = {
            400, 60, 30, 40, 30, 20, 10, 80, 20, 300, 300, 80, 80, 60, 40, 30,
            25, 30, 25, 20, 12, 6, 3, 150, 80, 50, 35, 25, 30, 15, 40, 15};

    /**
     * The row, from the bottom, and the column within its super block of each fragment of the coded order, by
     * quadrant and block: the Hilbert curve of the specification.
     */
    private static final int[] SB_ROWS = {0, 0, 1, 1, 2, 3, 3, 2, 2, 3, 3, 2, 1, 1, 0, 0};
    private static final int[] SB_COLUMNS = {0, 1, 1, 0, 0, 0, 1, 1, 2, 2, 3, 3, 3, 2, 2, 3};

    private static final double[][] COSINES = new double[8][8];

    /** The longest super block run one run code holds. */
    private static final int LONG_RUN = 4129;

    /** The largest magnitude a coefficient token codes. */
    private static final int MAX_COEFFICIENT = 580;

    /** The bits an intra macro block is assumed to cost beyond an inter one with the same sum of errors. */
    private static final int INTRA_BITS = 24;

    /** The largest sum of absolute errors of a fragment left uncoded although its residual quantizes to nonzero. */
    private static final int MAX_SKIPPED_ERROR = 3 * 64;

    /** The full-sample steps, in half samples, of the logarithmic motion search. */
    private static final int[] SEARCH_STEPS = {16, 8, 4};

    /** No logarithmic search steps. */
    private static final int[] NO_STEPS = {};

    /** The steps, in half samples, that refine the best point of the grid search. */
    private static final int[] GRID_STEPS = {4};

    /** The spacing, in half samples, of the grid search, and its largest vector component. */
    private static final int GRID_SPACING = 8;
    private static final int GRID_REACH = 28;

    /** The eight neighbors of a vector, as x and y offsets. */
    private static final int[] NEIGHBORS_X = {-1, 0, 1, -1, 1, -1, 0, 1};
    private static final int[] NEIGHBORS_Y = {-1, -1, -1, 0, 0, 1, 1, 1};

    /** The indices in the neighbor tables of the four neighbors that share an edge. */
    private static final int[] DIAMOND = {1, 3, 4, 6};

    static {
        for (var u = 0; u < 8; u++) {
            var scale = u == 0 ? StrictMath.sqrt(0.125) : 0.5;
            for (var i = 0; i < 8; i++) {
                COSINES[u][i] = scale * StrictMath.cos((2 * i + 1) * u * Math.PI / 16);
            }
        }
    }

    private final int width;
    private final int height;
    private final int pixelFormat;
    private final int qi;
    private final int keyframeInterval;
    private final int granuleShift;
    private final int shiftX;
    private final int shiftY;
    private final int frameWidth;
    private final int frameHeight;
    private final int[] hfrags = new int[3];
    private final int[] vfrags = new int[3];
    private final int[] fragOffset = new int[4];
    private final int[] codedOrder;
    private final byte[][] headers;
    private final TheoraSetup setup;
    private final TheoraFrameDecoder recon;
    private final int[] codes = new int[32];
    private final int[] lengths = new int[32];
    /** The bits of each motion vector component the variable-length code takes, by component + 31. */
    private final int[] mvBits = new int[63];
    /** The weight of one estimated bit against the sum of absolute errors. */
    private final int lambda;

    /** The source planes in the coded frame layout, bottom row first, the picture's edges repeated. */
    private final byte[][] source = new byte[3][];
    private final int[] sourceStride = new int[3];

    private final int[] quantized;
    private final boolean[] coded;
    private final byte[] refi;
    private final int[] reconDc;
    private final int[] fragMvX;
    private final int[] fragMvY;
    private final int[] codedList;
    private final int[] codedCount = new int[3];
    private final int[] tokenCount;
    private final int[] tokenPosition;
    private final int[] tokenValue;
    private final int[] tokenExtra;
    private final int[] tokenExtraBits;
    private final int[] nextToken;
    private final int[] predLast = new int[3];

    /** The mode of each macro block, coded or not; meaningful only for the macro blocks inside the frame. */
    private final int[] mbMode;
    /** Whether the frame codes the mode of each macro block, that is, codes one of its luma blocks. */
    private final boolean[] mbCoded;
    /** The vector each macro block codes, in half samples. */
    private final int[] mbMvX;
    private final int[] mbMvY;
    /** The best vector of the motion search of each macro block, in this frame and in the previous one. */
    private final int[] searchMvX;
    private final int[] searchMvY;
    private final int[] lastSearchMvX;
    private final int[] lastSearchMvY;
    private final int[] blockMvX = new int[4];
    private final int[] blockMvY = new int[4];
    private final int[] chromaMvX = new int[4];
    private final int[] chromaMvY = new int[4];
    private final int[] candidateX = new int[8];
    private final int[] candidateY = new int[8];
    private final int[] modeCount = new int[8];
    /** The estimated bits of each mode, from the scheme of the last inter frame. */
    private final int[] modeBits = new int[8];
    private final int[] rank = new int[8];
    private final int[] modeOrder = new int[8];
    private final boolean[] runFlags;

    private final double[] samples = new double[64];
    private final double[] rows = new double[64];
    /** The prediction error of the fragment being quantized, in sample order. */
    private final double[] errors = new double[64];
    private final TheoraBitWriter bits = new TheoraBitWriter();

    private int lastX;
    private int lastY;
    private int priorX;
    private int priorY;
    private int bestX;
    private int bestY;
    private int bestSad;
    private int chosenX;
    private int chosenY;
    private long frames;
    private int sinceKeyframe;
    private boolean keyframeRequested;
    private boolean keyframe;

    /**
     * Constructs an encoder and writes the three headers of its stream.
     *
     * @param width the picture width, from 1 to 1048560
     * @param height the picture height, from 1 to 1048560
     * @param pixelFormat the pixel format: 0 for 4:2:0, 2 for 4:2:2, 3 for 4:4:4
     * @param quality the quality index, from 0 (coarsest) to 63 (finest)
     * @param keyframeInterval the most frames from one key frame to the next, positive; 1 makes every frame a key
     * frame
     * @param frameRateNumerator the frame rate numerator, positive
     * @param frameRateDenominator the frame rate denominator, positive
     * @param aspectNumerator the pixel aspect ratio numerator, 0 when unknown
     * @param aspectDenominator the pixel aspect ratio denominator, 0 when unknown
     * @param vendor the vendor string of the comment header
     */
    public TheoraFrameEncoder(int width, int height, int pixelFormat, int quality, int keyframeInterval,
                              long frameRateNumerator, long frameRateDenominator, long aspectNumerator,
                              long aspectDenominator, String vendor) {
        this.width = width;
        this.height = height;
        this.pixelFormat = pixelFormat;
        this.qi = quality;
        this.keyframeInterval = keyframeInterval;
        this.granuleShift = granuleShift(keyframeInterval);
        this.shiftX = pixelFormat == 3 ? 0 : 1;
        this.shiftY = pixelFormat == 0 ? 1 : 0;
        this.frameWidth = width + 15 & ~15;
        this.frameHeight = height + 15 & ~15;
        var frags = 0;
        for (var pli = 0; pli < 3; pli++) {
            hfrags[pli] = frameWidth >> (pli == 0 ? 0 : shiftX) >> 3;
            vfrags[pli] = frameHeight >> (pli == 0 ? 0 : shiftY) >> 3;
            fragOffset[pli] = frags;
            frags += hfrags[pli] * vfrags[pli];
            sourceStride[pli] = hfrags[pli] << 3;
            source[pli] = new byte[sourceStride[pli] * (vfrags[pli] << 3)];
        }
        fragOffset[3] = frags;
        codedOrder = new int[frags];
        var count = 0;
        for (var pli = 0; pli < 3; pli++) {
            for (var y = 0; y < vfrags[pli]; y += 4) {
                for (var x = 0; x < hfrags[pli]; x += 4) {
                    for (var quad = 0; quad < 4; quad++) {
                        for (var bi = 0; bi < 4; bi++) {
                            var index = quad * 4 + bi;
                            var fy = y + SB_ROWS[index];
                            var fx = x + SB_COLUMNS[index];
                            if (fx < hfrags[pli] && fy < vfrags[pli]) {
                                codedOrder[count++] = fragOffset[pli] + fy * hfrags[pli] + fx;
                            }
                        }
                    }
                }
            }
        }
        huffmanCode();
        headers = new byte[][]{identification(frameRateNumerator, frameRateDenominator, aspectNumerator,
                aspectDenominator), comment(vendor), setupHeader()};
        setup = new TheoraSetup(headers[0], headers[2]);
        recon = new TheoraFrameDecoder(setup, ColorInfo.BT601_LIMITED);
        quantized = new int[frags * 64];
        coded = new boolean[frags];
        refi = new byte[frags];
        reconDc = new int[frags];
        fragMvX = new int[frags];
        fragMvY = new int[frags];
        codedList = new int[frags];
        tokenCount = new int[frags];
        tokenPosition = new int[frags * 66];
        tokenValue = new int[frags * 66];
        tokenExtra = new int[frags * 66];
        tokenExtraBits = new int[frags * 66];
        nextToken = new int[frags];
        var mbs = recon.mbCount;
        mbMode = new int[mbs];
        mbCoded = new boolean[mbs];
        mbMvX = new int[mbs];
        mbMvY = new int[mbs];
        searchMvX = new int[mbs];
        searchMvY = new int[mbs];
        lastSearchMvX = new int[mbs];
        lastSearchMvY = new int[mbs];
        runFlags = new boolean[Math.max(frags, recon.sbCount)];
        for (var v = -31; v <= 31; v++) {
            var magnitude = Math.abs(v);
            mvBits[v + 31] = magnitude <= 1 ? 3 : magnitude <= 3 ? 4 : magnitude <= 7 ? 6 : magnitude <= 15 ? 7 : 8;
        }
        // one bit costs as many absolute errors as a quarter of the luma inter quantizer step of the lowest AC
        // frequency (the tables hold 4 times the step)
        lambda = Math.max(1, setup.dequant[qi][0][1][1] >> 4);
        schemeBits(MODE_ALPHABETS[0]);
    }

    /**
     * {@return the granule shift that counts the frames of a key frame interval: the bits of the largest distance
     * from a key frame, and at least 6}
     *
     * @param keyframeInterval the key frame interval, positive
     */
    public static int granuleShift(int keyframeInterval) {
        return Math.max(6, 32 - Integer.numberOfLeadingZeros(keyframeInterval - 1));
    }

    /**
     * {@return the identification, comment and setup headers}
     */
    public byte[][] headers() {
        return new byte[][]{headers[0].clone(), headers[1].clone(), headers[2].clone()};
    }

    private static void signature(TheoraBitWriter writer, int type) {
        writer.write(type, 8);
        for (var c : "theora".getBytes(StandardCharsets.US_ASCII)) {
            writer.write(c, 8);
        }
    }

    private byte[] identification(long frameRateNumerator, long frameRateDenominator, long aspectNumerator,
                                  long aspectDenominator) {
        var writer = new TheoraBitWriter();
        signature(writer, 0x80);
        writer.write(3, 8);
        writer.write(2, 8);
        writer.write(1, 8);
        writer.write(frameWidth >> 4, 16);
        writer.write(frameHeight >> 4, 16);
        writer.write(width, 24);
        writer.write(height, 24);
        writer.write(0, 8);
        writer.write(frameHeight - height, 8);
        writer.write(frameRateNumerator, 32);
        writer.write(frameRateDenominator, 32);
        writer.write(aspectNumerator, 24);
        writer.write(aspectDenominator, 24);
        writer.write(0, 8);
        writer.write(0, 24);
        writer.write(qi, 6);
        writer.write(granuleShift, 5);
        writer.write(pixelFormat, 2);
        writer.write(0, 3);
        return writer.toByteArray();
    }

    private static byte[] comment(String vendor) {
        var text = vendor.getBytes(StandardCharsets.UTF_8);
        var header = new byte[7 + 4 + text.length + 4];
        header[0] = (byte) 0x81;
        System.arraycopy("theora".getBytes(StandardCharsets.US_ASCII), 0, header, 1, 6);
        header[7] = (byte) text.length;
        header[8] = (byte) (text.length >> 8);
        header[9] = (byte) (text.length >> 16);
        header[10] = (byte) (text.length >> 24);
        System.arraycopy(text, 0, header, 11, text.length);
        return header;
    }

    private byte[] setupHeader() {
        var writer = new TheoraBitWriter();
        signature(writer, 0x82);
        writer.write(5, 3);
        for (var limit : LOOP_FILTER_LIMITS) {
            writer.write(limit, 5);
        }
        writer.write(8, 4);
        for (var scale : AC_SCALE) {
            writer.write(scale, 9);
        }
        writer.write(7, 4);
        for (var scale : DC_SCALE) {
            writer.write(scale, 8);
        }
        writer.write(BASES.length - 1, 9);
        for (var base : BASES) {
            for (var value : base) {
                writer.write(value, 8);
            }
        }
        // the six ranges: intra Y, intra Cb (new), intra Cr (copies Cb), inter Y (new), inter Cb and Cr (copy Y)
        int[] matrices = {0, 1, -1, 2, -1, -1};
        for (var i = 0; i < 6; i++) {
            if (i > 0) {
                writer.bit(matrices[i] >= 0 ? 1 : 0);
                if (matrices[i] < 0) {
                    if (i >= 3) {
                        writer.bit(0);
                    }
                    continue;
                }
            }
            writer.write(matrices[i], 2);
            writer.write(62, 6);
            writer.write(matrices[i], 2);
        }
        for (var table = 0; table < 80; table++) {
            writeTree(writer, 0, 0);
        }
        return writer.toByteArray();
    }

    /**
     * Builds the static Huffman code from {@link #TOKEN_FREQUENCIES}: code lengths by Huffman's algorithm, ties
     * broken by token, then canonical codes.
     */
    private void huffmanCode() {
        record Node(long weight, int order, int token, Node zero, Node one) {
        }
        var queue = new PriorityQueue<Node>(Comparator.comparingLong(Node::weight).thenComparingInt(Node::order));
        for (var token = 0; token < 32; token++) {
            queue.add(new Node(TOKEN_FREQUENCIES[token], token, token, null, null));
        }
        var order = 32;
        while (queue.size() > 1) {
            var a = queue.poll();
            var b = queue.poll();
            queue.add(new Node(a.weight() + b.weight(), order++, -1, a, b));
        }
        var stack = new ArrayList<Node>();
        var depths = new ArrayList<Integer>();
        stack.add(queue.poll());
        depths.add(0);
        while (!stack.isEmpty()) {
            var node = stack.removeLast();
            var depth = depths.removeLast();
            if (node.token() >= 0) {
                lengths[node.token()] = depth;
            } else {
                stack.add(node.zero());
                depths.add(depth + 1);
                stack.add(node.one());
                depths.add(depth + 1);
            }
        }
        var code = 0;
        for (var length = 1; length <= 32; length++) {
            for (var token = 0; token < 32; token++) {
                if (lengths[token] == length) {
                    codes[token] = code++;
                }
            }
            code <<= 1;
        }
    }

    /**
     * Writes the subtree of the canonical code under {@code prefix}, {@code depth} bits long, depth first.
     */
    private void writeTree(TheoraBitWriter writer, int prefix, int depth) {
        for (var token = 0; token < 32; token++) {
            if (lengths[token] == depth && codes[token] == prefix) {
                writer.bit(1);
                writer.write(token, 5);
                return;
            }
        }
        writer.bit(0);
        writeTree(writer, prefix << 1, depth + 1);
        writeTree(writer, prefix << 1 | 1, depth + 1);
    }

    /**
     * Makes the next frame a key frame.
     */
    public void requestKeyframe() {
        keyframeRequested = true;
    }

    /**
     * {@return whether the last frame encoded is a key frame}
     */
    public boolean keyframe() {
        return keyframe;
    }

    /**
     * Encodes one picture as a key frame or an inter frame.
     *
     * @param picture the picture, of the encoder's size and pixel format
     * @return the frame's packet, empty for an inter frame that codes no block
     */
    public byte[] encode(YuvPicture picture) {
        for (var pli = 0; pli < 3; pli++) {
            loadSource(picture, pli);
        }
        var key = frames == 0 || keyframeRequested || sinceKeyframe + 1 >= keyframeInterval
                || !encodeInter();
        if (key) {
            encodeIntra();
            sinceKeyframe = 0;
            keyframeRequested = false;
        } else {
            sinceKeyframe++;
        }
        keyframe = key;
        frames++;
        var packet = bits.toByteArray();
        recon.advance(packet);
        if (recon.overran()) {
            throw new IllegalStateException("the Theora decoder read past the end of the encoder's packet");
        }
        return packet;
    }

    /**
     * Copies a plane of the picture into {@link #source}, bottom row first, repeating its last column and row up to
     * the coded frame's size.
     */
    private void loadSource(YuvPicture picture, int pli) {
        byte[] plane;
        int stride;
        int planeWidth;
        int planeHeight;
        if (pli == 0) {
            plane = picture.y();
            stride = picture.yStride();
            planeWidth = picture.width();
            planeHeight = picture.height();
        } else {
            plane = pli == 1 ? picture.u() : picture.v();
            stride = picture.uvStride();
            planeWidth = picture.chromaWidth();
            planeHeight = picture.chromaHeight();
        }
        var target = source[pli];
        var columns = sourceStride[pli];
        var rowsCoded = vfrags[pli] << 3;
        for (var row = 0; row < rowsCoded; row++) {
            var top = Math.min(rowsCoded - 1 - row, planeHeight - 1);
            var offset = row * columns;
            System.arraycopy(plane, top * stride, target, offset, planeWidth);
            Arrays.fill(target, offset + planeWidth, offset + columns, plane[top * stride + planeWidth - 1]);
        }
    }

    private void encodeIntra() {
        for (var frag : codedOrder) {
            coded[frag] = true;
            refi[frag] = FRAME_SELF;
            quantizeIntra(frag);
        }
        System.arraycopy(codedOrder, 0, codedList, 0, codedOrder.length);
        for (var pli = 0; pli < 3; pli++) {
            codedCount[pli] = fragOffset[pli + 1] - fragOffset[pli];
            predictDc(pli);
        }
        for (var ci = 0; ci < codedOrder.length; ci++) {
            tokenize(codedList[ci]);
        }
        bits.reset();
        bits.bit(0);
        bits.bit(0);
        bits.write(qi, 6);
        bits.bit(0);
        bits.write(0, 3);
        writeTokens();
    }

    /**
     * Codes the picture as an inter frame into {@link #bits}.
     *
     * @return {@code false}, writing nothing, if more than half of the macro blocks choose intra coding
     */
    private boolean encodeInter() {
        var golden = recon.gold != recon.prev;
        lastX = lastY = priorX = priorY = 0;
        Arrays.fill(modeCount, 0);
        var valid = 0;
        var intra = 0;
        for (var mbi = 0; mbi < recon.mbCount; mbi++) {
            if (recon.mbMaps[mbi * 12] < 0) {
                continue;
            }
            valid++;
            var mode = chooseMode(mbi, golden);
            if (mode == INTRA) {
                intra++;
            }
            codeMacroBlock(mbi, mode);
        }
        System.arraycopy(searchMvX, 0, lastSearchMvX, 0, searchMvX.length);
        System.arraycopy(searchMvY, 0, lastSearchMvY, 0, searchMvY.length);
        if (2 * intra > valid) {
            return false;
        }
        var count = 0;
        for (var pli = 0; pli < 3; pli++) {
            var start = count;
            for (var i = fragOffset[pli]; i < fragOffset[pli + 1]; i++) {
                var frag = codedOrder[i];
                if (coded[frag]) {
                    codedList[count++] = frag;
                }
            }
            codedCount[pli] = count - start;
            predictDc(pli);
        }
        for (var ci = 0; ci < count; ci++) {
            tokenize(codedList[ci]);
        }
        bits.reset();
        if (count == 0) {
            // a frame that codes no block is an empty packet, the dropped frame of the specification, which some
            // decoders accept only in this form
            return true;
        }
        bits.bit(0);
        bits.bit(1);
        bits.write(qi, 6);
        bits.bit(0);
        writeCodedFlags();
        writeModes();
        writeMotionVectors();
        writeTokens();
        return true;
    }

    /**
     * Chooses the mode of a macro block, leaving its vector in {@link #chosenX} and {@link #chosenY} and, for
     * {@code INTER_MV_FOUR}, the vectors of its luma blocks in {@link #blockMvX} and {@link #blockMvY}.
     */
    private int chooseMode(int mbi, boolean golden) {
        var f0 = recon.mbMaps[mbi * 12];
        var h = hfrags[0];
        var fy = f0 / h;
        var fx = f0 % h;
        var srcBase = (fy << 3) * sourceStride[0] + (fx << 3);
        var refBase = recon.origin[0] + (fy << 3) * recon.stride[0] + (fx << 3);
        var previous = recon.buffers[recon.prev][0];

        var mode = INTER_NOMV;
        var cost = sad(previous, refBase, srcBase, 16, 0, 0, Integer.MAX_VALUE) + lambda * modeBits[INTER_NOMV];
        chosenX = chosenY = 0;
        if ((lastX | lastY) != 0) {
            var candidate = sad(previous, refBase, srcBase, 16, lastX, lastY, cost)
                    + lambda * modeBits[INTER_MV_LAST];
            if (candidate < cost) {
                cost = candidate;
                mode = INTER_MV_LAST;
                chosenX = lastX;
                chosenY = lastY;
            }
        }
        if ((priorX | priorY) != 0 && (priorX != lastX || priorY != lastY)) {
            var candidate = sad(previous, refBase, srcBase, 16, priorX, priorY, cost)
                    + lambda * modeBits[INTER_MV_LAST2];
            if (candidate < cost) {
                cost = candidate;
                mode = INTER_MV_LAST2;
                chosenX = priorX;
                chosenY = priorY;
            }
        }

        var n = 0;
        candidateX[n] = lastX;
        candidateY[n++] = lastY;
        candidateX[n] = priorX;
        candidateY[n++] = priorY;
        candidateX[n] = lastSearchMvX[mbi];
        candidateY[n++] = lastSearchMvY[mbi];
        if (mbi > 0) {
            candidateX[n] = searchMvX[mbi - 1];
            candidateY[n++] = searchMvY[mbi - 1];
        }
        search(previous, refBase, srcBase, 16, n, true);
        var intraActivity = activity(srcBase);
        if (bestSad > intraActivity) {
            // the predicted vectors and the logarithmic search can miss a fast motion with no predictor yet, as in
            // the first inter frame of a pan; a grid over the whole range finds it before the block falls to intra
            gridSearch(previous, refBase, srcBase, 16);
        }
        var mbX = bestX;
        var mbY = bestY;
        var mbSad = bestSad;
        searchMvX[mbi] = mbX;
        searchMvY[mbi] = mbY;
        var candidate = mbSad + lambda * (modeBits[INTER_MV] + mvBits[mbX + 31] + mvBits[mbY + 31]);
        if (candidate < cost) {
            cost = candidate;
            mode = INTER_MV;
            chosenX = mbX;
            chosenY = mbY;
        }

        var fourCost = lambda * modeBits[INTER_MV_FOUR];
        for (var bi = 0; bi < 4 && fourCost < cost; bi++) {
            var offset = (bi >> 1) * 8;
            var column = (bi & 1) * 8;
            candidateX[0] = mbX;
            candidateY[0] = mbY;
            search(previous, refBase + offset * recon.stride[0] + column, srcBase + offset * sourceStride[0] + column,
                    8, 1, false);
            blockMvX[bi] = bestX;
            blockMvY[bi] = bestY;
            fourCost += bestSad + lambda * (mvBits[bestX + 31] + mvBits[bestY + 31]);
        }
        if (fourCost < cost) {
            cost = fourCost;
            mode = INTER_MV_FOUR;
        }

        if (golden) {
            var gold = recon.buffers[recon.gold][0];
            candidate = sad(gold, refBase, srcBase, 16, 0, 0, cost) + lambda * modeBits[GOLDEN_NOMV];
            if (candidate < cost) {
                cost = candidate;
                mode = GOLDEN_NOMV;
                chosenX = chosenY = 0;
            }
            candidateX[0] = 0;
            candidateY[0] = 0;
            candidateX[1] = mbX;
            candidateY[1] = mbY;
            search(gold, refBase, srcBase, 16, 2, false);
            candidate = bestSad + lambda * (modeBits[GOLDEN_MV] + mvBits[bestX + 31] + mvBits[bestY + 31]);
            if (candidate < cost && (bestX | bestY) != 0) {
                cost = candidate;
                mode = GOLDEN_MV;
                chosenX = bestX;
                chosenY = bestY;
            }
        }

        if (intraActivity + lambda * (modeBits[INTRA] + INTRA_BITS) < cost) {
            mode = INTRA;
            chosenX = chosenY = 0;
        }
        return mode;
    }

    /**
     * Finds the vector of least sum of absolute errors for a block of {@code size} luma samples, leaving it in
     * {@link #bestX}, {@link #bestY} and {@link #bestSad}: the best of the zero vector and the first {@code count}
     * candidates rounded to whole samples, refined by a logarithmic search when {@code wide}, then by unit steps while
     * they improve, then by half a sample.
     */
    private void search(byte[] reference, int refBase, int srcBase, int size, int count, boolean wide) {
        bestX = bestY = 0;
        bestSad = sad(reference, refBase, srcBase, size, 0, 0, Integer.MAX_VALUE);
        for (var i = 0; i < count; i++) {
            var x = Math.clamp(candidateX[i] & ~1, -30, 30);
            var y = Math.clamp(candidateY[i] & ~1, -30, 30);
            if (x != bestX || y != bestY) {
                consider(reference, refBase, srcBase, size, x, y);
            }
        }
        refine(reference, refBase, srcBase, size, wide ? SEARCH_STEPS : NO_STEPS);
    }

    /**
     * Improves the vector in {@link #bestX} and {@link #bestY} by a search over a grid of whole-sample vectors that
     * covers the vector range, refined as {@link #search} refines its candidates.
     */
    private void gridSearch(byte[] reference, int refBase, int srcBase, int size) {
        for (var y = -GRID_REACH; y <= GRID_REACH; y += GRID_SPACING) {
            for (var x = -GRID_REACH; x <= GRID_REACH; x += GRID_SPACING) {
                consider(reference, refBase, srcBase, size, x, y);
            }
        }
        refine(reference, refBase, srcBase, size, GRID_STEPS);
    }

    /**
     * Refines the vector in {@link #bestX} and {@link #bestY}: a logarithmic search with the given steps, then unit
     * steps while they improve, then half a sample.
     */
    private void refine(byte[] reference, int refBase, int srcBase, int size, int[] steps) {
        for (var step : steps) {
            var centerX = bestX;
            var centerY = bestY;
            for (var k = 0; k < 8; k++) {
                var x = centerX + step * NEIGHBORS_X[k];
                var y = centerY + step * NEIGHBORS_Y[k];
                if (x >= -30 && x <= 30 && y >= -30 && y <= 30) {
                    consider(reference, refBase, srcBase, size, x, y);
                }
            }
        }
        for (var iteration = 0; iteration < 16; iteration++) {
            var centerX = bestX;
            var centerY = bestY;
            for (var k : DIAMOND) {
                var x = centerX + 2 * NEIGHBORS_X[k];
                var y = centerY + 2 * NEIGHBORS_Y[k];
                if (x >= -30 && x <= 30 && y >= -30 && y <= 30) {
                    consider(reference, refBase, srcBase, size, x, y);
                }
            }
            if (bestX == centerX && bestY == centerY) {
                break;
            }
        }
        var centerX = bestX;
        var centerY = bestY;
        for (var k = 0; k < 8; k++) {
            var x = centerX + NEIGHBORS_X[k];
            var y = centerY + NEIGHBORS_Y[k];
            if (x >= -31 && x <= 31 && y >= -31 && y <= 31) {
                consider(reference, refBase, srcBase, size, x, y);
            }
        }
    }

    private void consider(byte[] reference, int refBase, int srcBase, int size, int x, int y) {
        var sad = sad(reference, refBase, srcBase, size, x, y, bestSad);
        if (sad < bestSad) {
            bestSad = sad;
            bestX = x;
            bestY = y;
        }
    }

    /**
     * {@return the sum of absolute differences between a square of luma source samples and its prediction by a
     * vector in half samples, or a value at least {@code limit} once the sum reaches it}
     */
    private int sad(byte[] reference, int refBase, int srcBase, int size, int mvx, int mvy, int limit) {
        var s = recon.stride[0];
        var ss = sourceStride[0];
        var src = source[0];
        var first = refBase + MVMAP[0][mvy + 31] * s + MVMAP[0][mvx + 31];
        var dx = MVMAP2[0][mvx + 31];
        var dy = MVMAP2[0][mvy + 31];
        var sum = 0;
        if ((dx | dy) == 0) {
            for (var i = 0; i < size; i++) {
                var r = first + i * s;
                var p = srcBase + i * ss;
                for (var j = 0; j < size; j++) {
                    sum += Math.abs((src[p + j] & 0xFF) - (reference[r + j] & 0xFF));
                }
                if (sum >= limit) {
                    return sum;
                }
            }
            return sum;
        }
        var second = first + dy * s + dx;
        for (var i = 0; i < size; i++) {
            var r = first + i * s;
            var r2 = second + i * s;
            var p = srcBase + i * ss;
            for (var j = 0; j < size; j++) {
                var prediction = (reference[r + j] & 0xFF) + (reference[r2 + j] & 0xFF) >> 1;
                sum += Math.abs((src[p + j] & 0xFF) - prediction);
            }
            if (sum >= limit) {
                return sum;
            }
        }
        return sum;
    }

    /**
     * {@return the sum of the absolute differences of the luma samples of a macro block from the mean of their 8x8
     * block, the estimate of the cost of intra coding}
     */
    private int activity(int srcBase) {
        var ss = sourceStride[0];
        var src = source[0];
        var total = 0;
        for (var bi = 0; bi < 4; bi++) {
            var base = srcBase + (bi >> 1) * 8 * ss + (bi & 1) * 8;
            var sum = 0;
            for (var i = 0; i < 8; i++) {
                for (var j = 0; j < 8; j++) {
                    sum += src[base + i * ss + j] & 0xFF;
                }
            }
            var mean = sum + 32 >> 6;
            for (var i = 0; i < 8; i++) {
                for (var j = 0; j < 8; j++) {
                    total += Math.abs((src[base + i * ss + j] & 0xFF) - mean);
                }
            }
        }
        return total;
    }

    /**
     * Quantizes the fragments of a macro block in its mode, marks which are coded and records its mode and vectors,
     * updating the last two vectors as the decoder does.
     */
    private void codeMacroBlock(int mbi, int mode) {
        var base = mbi * 12;
        var reference = FRAME_FOR_MODE[mode];
        var planes = recon.buffers[reference == FRAME_GOLD ? recon.gold : recon.prev];
        if (mode == INTER_MV_FOUR) {
            setChromaVectors();
        } else if (mode == INTER_NOMV || mode == GOLDEN_NOMV || mode == INTRA) {
            chosenX = chosenY = 0;
        }
        var lumaCoded = false;
        for (var mapi : MB_MAP_IDXS[pixelFormat]) {
            var frag = recon.mbMaps[base + mapi];
            var pli = mapi >> 2;
            int x;
            int y;
            if (mode == INTER_MV_FOUR) {
                x = pli == 0 ? blockMvX[mapi & 3] : chromaMvX[mapi & 3];
                y = pli == 0 ? blockMvY[mapi & 3] : chromaMvY[mapi & 3];
            } else {
                x = chosenX;
                y = chosenY;
            }
            boolean isCoded;
            if (mode == INTRA) {
                quantizeIntra(frag);
                isCoded = true;
            } else {
                var skip = quantizeInter(frag, pli, planes[pli], x, y, mode == INTER_NOMV);
                isCoded = mode != INTER_NOMV || !skip;
            }
            coded[frag] = isCoded;
            refi[frag] = isCoded ? (byte) reference : FRAME_NONE;
            fragMvX[frag] = x;
            fragMvY[frag] = y;
            if (pli == 0) {
                lumaCoded |= isCoded;
            }
        }
        mbMode[mbi] = mode;
        mbMvX[mbi] = chosenX;
        mbMvY[mbi] = chosenY;
        mbCoded[mbi] = lumaCoded;
        if (!lumaCoded) {
            return;
        }
        modeCount[mode]++;
        switch (mode) {
            case INTER_MV -> {
                priorX = lastX;
                priorY = lastY;
                lastX = chosenX;
                lastY = chosenY;
            }
            case INTER_MV_LAST2 -> {
                priorX = lastX;
                priorY = lastY;
                lastX = chosenX;
                lastY = chosenY;
            }
            case INTER_MV_FOUR -> {
                priorX = lastX;
                priorY = lastY;
                lastX = blockMvX[3];
                lastY = blockMvY[3];
            }
            default -> {
            }
        }
    }

    private static int divRoundPow2(int dividend, int shift, int round) {
        return dividend + (dividend < 0 ? -1 : 0) + round >> shift;
    }

    /**
     * Derives the chroma vectors of an {@code INTER_MV_FOUR} macro block from its four luma vectors, as the decoder
     * does.
     */
    private void setChromaVectors() {
        switch (pixelFormat) {
            case 0 -> {
                chromaMvX[0] = divRoundPow2(blockMvX[0] + blockMvX[1] + blockMvX[2] + blockMvX[3], 2, 2);
                chromaMvY[0] = divRoundPow2(blockMvY[0] + blockMvY[1] + blockMvY[2] + blockMvY[3], 2, 2);
            }
            case 2 -> {
                chromaMvX[0] = divRoundPow2(blockMvX[0] + blockMvX[1], 1, 1);
                chromaMvY[0] = divRoundPow2(blockMvY[0] + blockMvY[1], 1, 1);
                chromaMvX[2] = divRoundPow2(blockMvX[2] + blockMvX[3], 1, 1);
                chromaMvY[2] = divRoundPow2(blockMvY[2] + blockMvY[3], 1, 1);
            }
            default -> {
                System.arraycopy(blockMvX, 0, chromaMvX, 0, 4);
                System.arraycopy(blockMvY, 0, chromaMvY, 0, 4);
            }
        }
    }

    private int planeOf(int frag) {
        return frag < fragOffset[1] ? 0 : frag < fragOffset[2] ? 1 : 2;
    }

    /**
     * {@return the index in {@link #source} of the bottom left sample of a fragment}
     */
    private int sourceOffset(int frag, int pli) {
        var index = frag - fragOffset[pli];
        var h = hfrags[pli];
        return ((index / h) << 3) * sourceStride[pli] + ((index % h) << 3);
    }

    /**
     * Transforms and quantizes the samples of a fragment with the intra matrices.
     *
     * @return whether every quantized coefficient is zero
     */
    private boolean quantizeIntra(int frag) {
        var pli = planeOf(frag);
        var src = source[pli];
        var ss = sourceStride[pli];
        var offset = sourceOffset(frag, pli);
        for (var i = 0; i < 8; i++) {
            for (var j = 0; j < 8; j++) {
                samples[i * 8 + j] = (src[offset + i * ss + j] & 0xFF) - 128;
            }
        }
        forwardDct();
        var quant = setup.dequant[qi][pli][0];
        var base = frag * 64;
        var zero = true;
        for (var zzi = 0; zzi < 64; zzi++) {
            var coefficient = 4 * samples[TheoraSetup.ZIG_ZAG[zzi]] / quant[zzi];
            var value = (int) Math.max(-MAX_COEFFICIENT, Math.min(MAX_COEFFICIENT, Math.round(coefficient)));
            quantized[base + zzi] = value;
            zero &= value == 0;
        }
        return zero;
    }

    /**
     * Transforms and quantizes the error of the prediction of a fragment by a vector with the inter matrices.
     *
     * @param skippable whether the fragment may be left uncoded, its prediction being the previous frame's samples
     * @return whether the fragment is best left uncoded: every quantized coefficient is zero or, when
     * {@code skippable}, the error is small and coding the coefficients reduces it by less than the estimated cost
     * of their tokens
     */
    private boolean quantizeInter(int frag, int pli, byte[] reference, int mvx, int mvy, boolean skippable) {
        var src = source[pli];
        var ss = sourceStride[pli];
        var offset = sourceOffset(frag, pli);
        var s = recon.stride[pli];
        var quarterX = pli != 0 && shiftX != 0 ? 1 : 0;
        var quarterY = pli != 0 && shiftY != 0 ? 1 : 0;
        var first = recon.bufferOffset(frag, pli) + MVMAP[quarterY][mvy + 31] * s + MVMAP[quarterX][mvx + 31];
        var second = first + MVMAP2[quarterY][mvy + 31] * s + MVMAP2[quarterX][mvx + 31];
        for (var i = 0; i < 8; i++) {
            var r = first + i * s;
            var r2 = second + i * s;
            var p = offset + i * ss;
            for (var j = 0; j < 8; j++) {
                var prediction = (reference[r + j] & 0xFF) + (reference[r2 + j] & 0xFF) >> 1;
                samples[i * 8 + j] = (src[p + j] & 0xFF) - prediction;
            }
        }
        System.arraycopy(samples, 0, errors, 0, 64);
        forwardDct();
        var quant = setup.dequant[qi][pli][1];
        var base = frag * 64;
        var zero = true;
        var tokenBits = 0;
        for (var zzi = 0; zzi < 64; zzi++) {
            var coefficient = 4 * samples[TheoraSetup.ZIG_ZAG[zzi]] / quant[zzi];
            var magnitude = (int) (Math.abs(coefficient) + 0.5);
            magnitude = Math.min(MAX_COEFFICIENT, magnitude);
            var value = coefficient < 0 ? -magnitude : magnitude;
            quantized[base + zzi] = value;
            if (value != 0) {
                zero = false;
                tokenBits += 3 + 2 * (32 - Integer.numberOfLeadingZeros(magnitude));
            }
        }
        if (zero || !skippable) {
            return zero;
        }
        for (var zzi = 0; zzi < 64; zzi++) {
            samples[TheoraSetup.ZIG_ZAG[zzi]] = quantized[base + zzi] * quant[zzi] / 4.0;
        }
        inverseDct();
        var skipped = 0.0;
        var coded = 0.0;
        for (var i = 0; i < 64; i++) {
            skipped += Math.abs(errors[i]);
            coded += Math.abs(errors[i] - samples[i]);
        }
        // a block left uncoded keeps its error in the frames that follow, so only small errors are left, against half
        // the estimated cost of the tokens
        return skipped <= MAX_SKIPPED_ERROR && 2 * skipped <= 2 * coded + lambda * (tokenBits + 2);
    }

    /**
     * Replaces the coefficients in {@link #samples}, in natural order, with their orthonormal inverse DCT.
     */
    private void inverseDct() {
        for (var u = 0; u < 8; u++) {
            for (var j = 0; j < 8; j++) {
                var sum = 0.0;
                for (var v = 0; v < 8; v++) {
                    sum += COSINES[v][j] * samples[u * 8 + v];
                }
                rows[u * 8 + j] = sum;
            }
        }
        for (var i = 0; i < 8; i++) {
            for (var j = 0; j < 8; j++) {
                var sum = 0.0;
                for (var u = 0; u < 8; u++) {
                    sum += COSINES[u][i] * rows[u * 8 + j];
                }
                samples[i * 8 + j] = sum;
            }
        }
    }

    /**
     * Replaces {@link #samples} with its orthonormal two-dimensional DCT, natural index {@code u * 8 + v} for the
     * vertical frequency {@code u} along the block rows and the horizontal frequency {@code v}.
     */
    private void forwardDct() {
        for (var i = 0; i < 8; i++) {
            for (var v = 0; v < 8; v++) {
                var sum = 0.0;
                for (var j = 0; j < 8; j++) {
                    sum += COSINES[v][j] * samples[i * 8 + j];
                }
                rows[i * 8 + v] = sum;
            }
        }
        for (var u = 0; u < 8; u++) {
            for (var v = 0; v < 8; v++) {
                var sum = 0.0;
                for (var i = 0; i < 8; i++) {
                    sum += COSINES[u][i] * rows[i * 8 + v];
                }
                samples[u * 8 + v] = sum;
            }
        }
    }

    /**
     * Replaces the quantized DC of every coded fragment of a plane by its difference from the prediction the decoder
     * forms from the coded neighbors of the same reference frame, keeping the DC the decoder reconstructs in
     * {@link #reconDc}.
     */
    private void predictDc(int pli) {
        Arrays.fill(predLast, 0);
        var h = hfrags[pli];
        var frag = fragOffset[pli];
        var dc = reconDc;
        for (var y = 0; y < vfrags[pli]; y++) {
            if (y == 0) {
                for (var x = 0; x < h; x++, frag++) {
                    if (coded[frag]) {
                        var r = refi[frag];
                        predLast[r] = residualDc(frag, predLast[r]);
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
                    predLast[r] = residualDc(frag, pred);
                    lRef = r;
                } else {
                    lRef = -1;
                }
                ulRef = uRef;
                uRef = urRef;
            }
        }
    }

    /**
     * Replaces the quantized DC of a fragment by its difference from a prediction, limited to the range a token
     * codes.
     *
     * @return the DC the decoder reconstructs
     */
    private int residualDc(int frag, int prediction) {
        var index = frag * 64;
        var difference = Math.clamp(quantized[index] - prediction, -MAX_COEFFICIENT, MAX_COEFFICIENT);
        quantized[index] = difference;
        var dc = (short) (difference + prediction);
        reconDc[frag] = dc;
        return dc;
    }

    /**
     * Writes the coded block flags: the partially coded super blocks, the fully coded ones among the others, then
     * the flag of every fragment of a partially coded super block.
     */
    private void writeCodedFlags() {
        var sbCount = recon.sbCount;
        var maps = recon.sbMaps;
        var partial = 0;
        var full = 0;
        var blocks = 0;
        for (var sbi = 0; sbi < sbCount; sbi++) {
            runFlags[sbi] = isPartial(sbi);
            if (runFlags[sbi]) {
                partial++;
            }
        }
        writeLongRuns(sbCount);
        if (partial < sbCount) {
            for (var sbi = 0; sbi < sbCount; sbi++) {
                if (!isPartial(sbi)) {
                    runFlags[full++] = coded[firstFragment(sbi)];
                }
            }
            writeLongRuns(full);
        }
        if (partial == 0) {
            return;
        }
        for (var sbi = 0; sbi < sbCount; sbi++) {
            if (!isPartial(sbi)) {
                continue;
            }
            for (var k = 0; k < 16; k++) {
                var frag = maps[sbi * 16 + k];
                if (frag >= 0) {
                    runFlags[blocks++] = coded[frag];
                }
            }
        }
        bits.bit(runFlags[0] ? 1 : 0);
        var i = 0;
        while (i < blocks) {
            var j = i;
            while (j < blocks && runFlags[j] == runFlags[i]) {
                j++;
            }
            writeBlockRun(j - i);
            i = j;
        }
    }

    private boolean isPartial(int sbi) {
        var maps = recon.sbMaps;
        var any = false;
        var all = true;
        for (var k = 0; k < 16; k++) {
            var frag = maps[sbi * 16 + k];
            if (frag >= 0) {
                any |= coded[frag];
                all &= coded[frag];
            }
        }
        return any && !all;
    }

    private int firstFragment(int sbi) {
        var maps = recon.sbMaps;
        for (var k = 0; k < 16; k++) {
            if (maps[sbi * 16 + k] >= 0) {
                return maps[sbi * 16 + k];
            }
        }
        throw new IllegalStateException("empty super block " + sbi);
    }

    /**
     * Writes the first {@code count} entries of {@link #runFlags}: the first flag, then the lengths of the runs of
     * equal flags, a run of the longest length followed by the explicit flag of the next one.
     */
    private void writeLongRuns(int count) {
        bits.bit(runFlags[0] ? 1 : 0);
        var i = 0;
        while (i < count) {
            var j = i;
            while (j < count && runFlags[j] == runFlags[i]) {
                j++;
            }
            var length = j - i;
            while (length > 0) {
                var chunk = Math.min(length, LONG_RUN);
                writeSuperBlockRun(chunk);
                i += chunk;
                length -= chunk;
                if (chunk == LONG_RUN && i < count) {
                    bits.bit(runFlags[i] ? 1 : 0);
                }
            }
        }
    }

    private void writeSuperBlockRun(int run) {
        if (run == 1) {
            bits.bit(0);
        } else if (run <= 3) {
            bits.write(0b10, 2);
            bits.write(run - 2, 1);
        } else if (run <= 5) {
            bits.write(0b110, 3);
            bits.write(run - 4, 1);
        } else if (run <= 9) {
            bits.write(0b1110, 4);
            bits.write(run - 6, 2);
        } else if (run <= 17) {
            bits.write(0b11110, 5);
            bits.write(run - 10, 3);
        } else if (run <= 33) {
            bits.write(0b111110, 6);
            bits.write(run - 18, 4);
        } else {
            bits.write(0b111111, 6);
            bits.write(run - 34, 12);
        }
    }

    /**
     * Writes the length of a run of fragment flags, from 1 to 30: a run within a partially coded super block holds
     * at most 15 fragments, so one across two of them at most 30.
     */
    private void writeBlockRun(int run) {
        if (run <= 2) {
            bits.write(0, 1);
            bits.write(run - 1, 1);
        } else if (run <= 4) {
            bits.write(0b10, 2);
            bits.write(run - 3, 1);
        } else if (run <= 6) {
            bits.write(0b110, 3);
            bits.write(run - 5, 1);
        } else if (run <= 10) {
            bits.write(0b1110, 4);
            bits.write(run - 7, 2);
        } else if (run <= 14) {
            bits.write(0b11110, 5);
            bits.write(run - 11, 2);
        } else if (run <= 30) {
            bits.write(0b11111, 5);
            bits.write(run - 15, 4);
        } else {
            throw new IllegalStateException("block run " + run);
        }
    }

    private static int unaryBits(int rank) {
        return rank < 7 ? rank + 1 : 7;
    }

    /**
     * Sets {@link #rank} to the ranks of an alphabet and {@link #modeBits} to the unary code lengths they give.
     */
    private void schemeBits(int[] alphabet) {
        for (var i = 0; i < 8; i++) {
            rank[alphabet[i]] = i;
        }
        for (var m = 0; m < 8; m++) {
            modeBits[m] = unaryBits(rank[m]);
        }
    }

    /**
     * Writes the mode scheme of least cost for the modes counted, then the mode of every macro block that codes a
     * luma fragment.
     */
    private void writeModes() {
        var total = 0;
        for (var count : modeCount) {
            total += count;
        }
        for (var m = 0; m < 8; m++) {
            var i = m;
            while (i > 0 && modeCount[modeOrder[i - 1]] < modeCount[m]) {
                modeOrder[i] = modeOrder[i - 1];
                i--;
            }
            modeOrder[i] = m;
        }
        var customCost = 24;
        for (var i = 0; i < 8; i++) {
            customCost += modeCount[modeOrder[i]] * unaryBits(i);
        }
        var scheme = 0;
        var best = customCost;
        for (var s = 1; s <= 6; s++) {
            var alphabet = MODE_ALPHABETS[s - 1];
            var cost = 0;
            for (var i = 0; i < 8; i++) {
                cost += modeCount[alphabet[i]] * unaryBits(i);
            }
            if (cost < best) {
                best = cost;
                scheme = s;
            }
        }
        if (3 * total < best) {
            scheme = 7;
        }
        bits.write(scheme, 3);
        if (scheme == 0) {
            for (var i = 0; i < 8; i++) {
                rank[modeOrder[i]] = i;
            }
            for (var m = 0; m < 8; m++) {
                bits.write(rank[m], 3);
                modeBits[m] = unaryBits(rank[m]);
            }
        } else {
            schemeBits(MODE_ALPHABETS[scheme - 1]);
        }
        for (var mbi = 0; mbi < recon.mbCount; mbi++) {
            if (recon.mbMaps[mbi * 12] < 0 || !mbCoded[mbi]) {
                continue;
            }
            var r = rank[mbMode[mbi]];
            if (scheme == 7) {
                bits.write(r, 3);
            } else {
                for (var i = 0; i < r; i++) {
                    bits.bit(1);
                }
                if (r < 7) {
                    bits.bit(0);
                }
            }
        }
        if (scheme == 7) {
            Arrays.fill(modeBits, 3);
        }
    }

    /**
     * Writes the motion vectors of the macro blocks, in the variable-length or the fixed-length code, whichever is
     * shorter.
     */
    private void writeMotionVectors() {
        var variable = 0;
        var components = 0;
        for (var mbi = 0; mbi < recon.mbCount; mbi++) {
            if (recon.mbMaps[mbi * 12] < 0 || !mbCoded[mbi]) {
                continue;
            }
            switch (mbMode[mbi]) {
                case INTER_MV, GOLDEN_MV -> {
                    variable += mvBits[mbMvX[mbi] + 31] + mvBits[mbMvY[mbi] + 31];
                    components += 2;
                }
                case INTER_MV_FOUR -> {
                    for (var bi = 0; bi < 4; bi++) {
                        var frag = recon.mbMaps[mbi * 12 + bi];
                        if (coded[frag]) {
                            variable += mvBits[fragMvX[frag] + 31] + mvBits[fragMvY[frag] + 31];
                            components += 2;
                        }
                    }
                }
                default -> {
                }
            }
        }
        var fixed = 6 * components < variable;
        bits.bit(fixed ? 1 : 0);
        for (var mbi = 0; mbi < recon.mbCount; mbi++) {
            if (recon.mbMaps[mbi * 12] < 0 || !mbCoded[mbi]) {
                continue;
            }
            switch (mbMode[mbi]) {
                case INTER_MV, GOLDEN_MV -> {
                    writeMotionComponent(mbMvX[mbi], fixed);
                    writeMotionComponent(mbMvY[mbi], fixed);
                }
                case INTER_MV_FOUR -> {
                    for (var bi = 0; bi < 4; bi++) {
                        var frag = recon.mbMaps[mbi * 12 + bi];
                        if (coded[frag]) {
                            writeMotionComponent(fragMvX[frag], fixed);
                            writeMotionComponent(fragMvY[frag], fixed);
                        }
                    }
                }
                default -> {
                }
            }
        }
    }

    private void writeMotionComponent(int value, boolean fixed) {
        var magnitude = Math.abs(value);
        var sign = value < 0 ? 1 : 0;
        if (fixed) {
            bits.write(magnitude << 1 | sign, 6);
            return;
        }
        switch (magnitude) {
            case 0 -> bits.write(0, 3);
            case 1 -> bits.write(sign == 0 ? 1 : 2, 3);
            case 2 -> {
                bits.write(3, 3);
                bits.bit(sign);
            }
            case 3 -> {
                bits.write(4, 3);
                bits.bit(sign);
            }
            default -> {
                if (magnitude <= 7) {
                    bits.write(5, 3);
                    bits.write(magnitude - 4, 2);
                } else if (magnitude <= 15) {
                    bits.write(6, 3);
                    bits.write(magnitude - 8, 3);
                } else {
                    bits.write(7, 3);
                    bits.write(magnitude - 16, 4);
                }
                bits.bit(sign);
            }
        }
    }

    private void addToken(int frag, int position, int token, int extra, int extraBits) {
        var slot = frag * 66 + tokenCount[frag]++;
        tokenPosition[slot] = position;
        tokenValue[slot] = token;
        tokenExtra[slot] = extra;
        tokenExtraBits[slot] = extraBits;
    }

    private void tokenize(int frag) {
        tokenCount[frag] = 0;
        nextToken[frag] = 0;
        var base = frag * 64;
        var position = 0;
        while (position < 64) {
            var next = position;
            while (next < 64 && quantized[base + next] == 0) {
                next++;
            }
            if (next == 64) {
                addToken(frag, position, -1, 0, 0);
                return;
            }
            var run = next - position;
            var value = quantized[base + next];
            var magnitude = Math.abs(value);
            var sign = value < 0 ? 1 : 0;
            if (run == 0) {
                valueToken(frag, position, magnitude, sign);
            } else if (magnitude == 1 && run <= 5) {
                addToken(frag, position, 22 + run, sign, 1);
            } else if (magnitude == 1 && run <= 9) {
                addToken(frag, position, 28, sign << 2 | run - 6, 3);
            } else if (magnitude == 1 && run <= 17) {
                addToken(frag, position, 29, sign << 3 | run - 10, 4);
            } else if (magnitude <= 3 && run == 1) {
                addToken(frag, position, 30, sign << 1 | magnitude - 2, 2);
            } else if (magnitude <= 3 && run <= 3) {
                addToken(frag, position, 31, sign << 2 | (magnitude - 2) << 1 | run - 2, 3);
            } else {
                if (run <= 8) {
                    addToken(frag, position, 7, run - 1, 3);
                } else {
                    addToken(frag, position, 8, run - 1, 6);
                }
                valueToken(frag, next, magnitude, sign);
            }
            position = next + 1;
        }
    }

    private void valueToken(int frag, int position, int magnitude, int sign) {
        if (magnitude <= 2) {
            addToken(frag, position, 7 + 2 * magnitude + sign, 0, 0);
        } else if (magnitude <= 6) {
            addToken(frag, position, 10 + magnitude, sign, 1);
        } else if (magnitude <= 8) {
            addToken(frag, position, 17, sign << 1 | magnitude - 7, 2);
        } else if (magnitude <= 12) {
            addToken(frag, position, 18, sign << 2 | magnitude - 9, 3);
        } else if (magnitude <= 20) {
            addToken(frag, position, 19, sign << 3 | magnitude - 13, 4);
        } else if (magnitude <= 36) {
            addToken(frag, position, 20, sign << 4 | magnitude - 21, 5);
        } else if (magnitude <= 68) {
            addToken(frag, position, 21, sign << 5 | magnitude - 37, 6);
        } else {
            addToken(frag, position, 22, sign << 9 | magnitude - 69, 10);
        }
    }

    private void writeCode(int token) {
        bits.write(codes[token], lengths[token]);
    }

    private void writeEobRun(int run) {
        if (run <= 3) {
            writeCode(run - 1);
        } else if (run <= 7) {
            writeCode(3);
            bits.write(run - 4, 2);
        } else if (run <= 15) {
            writeCode(4);
            bits.write(run - 8, 3);
        } else if (run <= 31) {
            writeCode(5);
            bits.write(run - 16, 4);
        } else {
            writeCode(6);
            bits.write(run, 12);
        }
    }

    /**
     * Writes the tokens of the coded fragments coefficient by coefficient and plane by plane, in the order the
     * decoder visits the blocks, the end-of-block tokens of consecutive visits merged into runs.
     */
    private void writeTokens() {
        var eobRun = 0;
        for (var zzi = 0; zzi < 64; zzi++) {
            if (zzi < 2) {
                // the decoder reads a run's token at its first block, so a run still open when the AC table
                // indices follow the DC tokens ends there
                if (eobRun > 0) {
                    writeEobRun(eobRun);
                    eobRun = 0;
                }
                bits.write(0, 4);
                bits.write(0, 4);
            }
            var start = 0;
            for (var pli = 0; pli < 3; pli++) {
                var end = start + codedCount[pli];
                for (var ci = start; ci < end; ci++) {
                    var frag = codedList[ci];
                    if (nextToken[frag] >= tokenCount[frag]) {
                        continue;
                    }
                    var slot = frag * 66 + nextToken[frag];
                    if (tokenPosition[slot] != zzi) {
                        continue;
                    }
                    nextToken[frag]++;
                    var token = tokenValue[slot];
                    if (token < 0) {
                        if (++eobRun == 4095) {
                            writeEobRun(eobRun);
                            eobRun = 0;
                        }
                        continue;
                    }
                    if (eobRun > 0) {
                        writeEobRun(eobRun);
                        eobRun = 0;
                    }
                    writeCode(token);
                    bits.write(tokenExtra[slot], tokenExtraBits[slot]);
                }
                start = end;
            }
        }
        if (eobRun > 0) {
            writeEobRun(eobRun);
        }
    }

    /**
     * {@return the coded frame width, a multiple of 16}
     */
    public int frameWidth() {
        return frameWidth;
    }

    /**
     * {@return the coded frame height, a multiple of 16}
     */
    public int frameHeight() {
        return frameHeight;
    }
}
