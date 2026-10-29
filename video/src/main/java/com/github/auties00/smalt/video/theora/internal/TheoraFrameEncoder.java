/* Ports libtheora 1.2.0 (the VP3 quantization parameters of lib/encode.c). Copyright (C) 2002-2009 Xiph.org
   Foundation; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.theora.internal;

import com.github.auties00.smalt.image.YuvPicture;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.PriorityQueue;

/**
 * Encodes pictures as Theora intra frames, with the VP3 quantization parameters of libtheora and one static
 * Huffman code for every token table.
 *
 * <p>Each frame is a key frame coded at one quality index: the samples of every 8x8 block go through an orthonormal
 * forward DCT, are quantized with the dequantization tables the setup header defines, DC-predicted as the decoder
 * predicts them and tokenized coefficient by coefficient in the coded order of the fragments, with end-of-block
 * runs across blocks. The picture is placed at the top left of a coded frame of whole macro blocks, the samples
 * beyond it repeating the last column and row.
 *
 * <p>Instances are not thread-safe.
 */
public final class TheoraFrameEncoder {
    // TODO: inter frames (motion search and the macro block modes against the previous and golden frames),
    // Huffman codes fitted to each stream and rate control; every frame is an intra frame at one quality index.
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
    private final int shiftX;
    private final int shiftY;
    private final int frameWidth;
    private final int frameHeight;
    private final int[] hfrags = new int[3];
    private final int[] vfrags = new int[3];
    private final int[] fragOffset = new int[4];
    private final int[] codedOrder;
    private final int[] codedPerPlane = new int[3];
    private final byte[][] headers;
    private final TheoraSetup setup;
    private final int[] codes = new int[32];
    private final int[] lengths = new int[32];

    private final int[] quantized;
    private final int[] tokenCount;
    private final int[] tokenPosition;
    private final int[] tokenValue;
    private final int[] tokenExtra;
    private final int[] tokenExtraBits;
    private final int[] nextToken;
    private final int[] predictionRows;
    private final double[] samples = new double[64];
    private final double[] rows = new double[64];
    private final TheoraBitWriter bits = new TheoraBitWriter();

    /**
     * Constructs an encoder and writes the three headers of its stream.
     *
     * @param width the picture width, from 1 to 1048560
     * @param height the picture height, from 1 to 1048560
     * @param pixelFormat the pixel format: 0 for 4:2:0, 2 for 4:2:2, 3 for 4:4:4
     * @param quality the quality index, from 0 (coarsest) to 63 (finest)
     * @param frameRateNumerator the frame rate numerator, positive
     * @param frameRateDenominator the frame rate denominator, positive
     * @param aspectNumerator the pixel aspect ratio numerator, 0 when unknown
     * @param aspectDenominator the pixel aspect ratio denominator, 0 when unknown
     * @param vendor the vendor string of the comment header
     */
    public TheoraFrameEncoder(int width, int height, int pixelFormat, int quality, long frameRateNumerator,
                              long frameRateDenominator, long aspectNumerator, long aspectDenominator,
                              String vendor) {
        this.width = width;
        this.height = height;
        this.pixelFormat = pixelFormat;
        this.qi = quality;
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
        }
        fragOffset[3] = frags;
        codedOrder = new int[frags];
        var count = 0;
        for (var pli = 0; pli < 3; pli++) {
            var start = count;
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
            codedPerPlane[pli] = count - start;
        }
        huffmanCode();
        headers = new byte[][]{identification(frameRateNumerator, frameRateDenominator, aspectNumerator,
                aspectDenominator), comment(vendor), setupHeader()};
        setup = new TheoraSetup(headers[0], headers[2]);
        quantized = new int[frags * 64];
        tokenCount = new int[frags];
        tokenPosition = new int[frags * 66];
        tokenValue = new int[frags * 66];
        tokenExtra = new int[frags * 66];
        tokenExtraBits = new int[frags * 66];
        nextToken = new int[frags];
        predictionRows = new int[2 * hfrags[0]];
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
        writer.write(6, 5);
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
     * Encodes one picture as an intra frame.
     *
     * @param picture the picture, of the encoder's size and pixel format
     * @return the frame's packet
     */
    public byte[] encode(YuvPicture picture) {
        for (var pli = 0; pli < 3; pli++) {
            transform(picture, pli);
        }
        for (var pli = 0; pli < 3; pli++) {
            predictDc(pli);
        }
        for (var ci = 0; ci < codedOrder.length; ci++) {
            tokenize(ci, codedOrder[ci]);
        }
        bits.reset();
        bits.bit(0);
        bits.bit(0);
        bits.write(qi, 6);
        bits.bit(0);
        bits.write(0, 3);
        writeTokens();
        return bits.toByteArray();
    }

    private void transform(YuvPicture picture, int pli) {
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
        var rowsCoded = vfrags[pli] << 3;
        var quant = setup.dequant[qi][pli][0];
        for (var fy = 0; fy < vfrags[pli]; fy++) {
            for (var fx = 0; fx < hfrags[pli]; fx++) {
                for (var i = 0; i < 8; i++) {
                    var top = Math.min(rowsCoded - 1 - (fy * 8 + i), planeHeight - 1);
                    for (var j = 0; j < 8; j++) {
                        var x = Math.min(fx * 8 + j, planeWidth - 1);
                        samples[i * 8 + j] = (plane[top * stride + x] & 0xFF) - 128;
                    }
                }
                forwardDct();
                var base = (fragOffset[pli] + fy * hfrags[pli] + fx) * 64;
                for (var zzi = 0; zzi < 64; zzi++) {
                    var coefficient = 4 * samples[TheoraSetup.ZIG_ZAG[zzi]] / quant[zzi];
                    quantized[base + zzi] = (int) Math.max(-580, Math.min(580, Math.round(coefficient)));
                }
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
     * Replaces the quantized DC of every fragment of a plane by its difference from the prediction the decoder
     * forms, every fragment being an intra fragment.
     */
    private void predictDc(int pli) {
        var h = hfrags[pli];
        var v = vfrags[pli];
        var first = fragOffset[pli];
        var dcs = predictionRows;
        var last = 0;
        for (var y = 0; y < v; y++) {
            var current = (y & 1) * h;
            var above = (y + 1 & 1) * h;
            for (var x = 0; x < h; x++) {
                var index = (first + y * h + x) * 64;
                var dc = quantized[index];
                int pred;
                if (y == 0) {
                    pred = last;
                } else {
                    var l = x > 0;
                    var ur = x + 1 < h;
                    var mask = (l ? 1 : 0) | (l ? 2 : 0) | 4 | (ur ? 8 : 0);
                    pred = switch (mask) {
                        case 4, 12 -> dcs[above + x];
                        case 7, 15 -> {
                            var p0 = dcs[current + x - 1];
                            var p1 = dcs[above + x - 1];
                            var p2 = dcs[above + x];
                            var p = (29 * (p0 + p2) - 26 * p1) / 32;
                            if (Math.abs(p - p2) > 128) {
                                p = p2;
                            } else if (Math.abs(p - p0) > 128) {
                                p = p0;
                            } else if (Math.abs(p - p1) > 128) {
                                p = p1;
                            }
                            yield p;
                        }
                        default -> throw new IllegalStateException("prediction case " + mask);
                    };
                }
                dcs[current + x] = dc;
                last = dc;
                quantized[index] = dc - pred;
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

    private void tokenize(int ci, int frag) {
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
     * Writes the tokens coefficient by coefficient and plane by plane, in the order the decoder visits the blocks,
     * the end-of-block tokens of consecutive visits merged into runs.
     */
    private void writeTokens() {
        var eobRun = 0;
        for (var zzi = 0; zzi < 64; zzi++) {
            if (zzi < 2) {
                bits.write(0, 4);
                bits.write(0, 4);
            }
            var start = 0;
            for (var pli = 0; pli < 3; pli++) {
                var end = start + codedPerPlane[pli];
                for (var ci = start; ci < end; ci++) {
                    var frag = codedOrder[ci];
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
