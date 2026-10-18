package com.github.auties00.smalt.image.webp.internal.lossless;

import java.util.ArrayList;
import java.util.List;

/**
 * Encodes ARGB images as WebP lossless (VP8L) bitstreams.
 *
 * <p>The encoder first analyzes the image: whether it has at most 256 colors (so the color
 * indexing transform applies, with 2, 4 or 8 indices bundled per pixel for small palettes) and
 * which combination of transforms minimizes a quick entropy estimate. For each candidate
 * configuration it applies the transforms (subtract-green, predictor with per-tile modes,
 * cross-color with per-tile multipliers), parses the pixels into LZ77 symbols with an optional
 * color cache, clusters the tile histograms into meta prefix codes, writes everything and keeps
 * the smallest result. The method (0 to 6) trades speed for size: it controls the tile sizes,
 * the search effort and how many configurations are tried; the quality (0 to 100) controls the
 * LZ77 and clustering effort.
 *
 * @implNote This implementation follows libwebp's {@code src/enc/vp8l_enc.c} (BSD-3) without
 * near-lossless coding and multi-threading: the analysis ({@code AnalyzeEntropy}), the crunch
 * configurations of each method, the tile-size heuristics and the bitstream layout are the same,
 * so the output closely tracks {@code cwebp -lossless} at equal method and quality.
 */
public final class Vp8lEncoder {
    /**
     * The entropy mode without transforms.
     */
    private static final int DIRECT = 0;

    /**
     * The entropy mode with the predictor.
     */
    private static final int SPATIAL = 1;

    /**
     * The entropy mode with subtract-green.
     */
    private static final int SUB_GREEN = 2;

    /**
     * The entropy mode with the predictor and subtract-green.
     */
    private static final int SPATIAL_SUB_GREEN = 3;

    /**
     * The entropy mode with color indexing.
     */
    private static final int PALETTE = 4;

    /**
     * The entropy mode with color indexing and the predictor.
     */
    private static final int PALETTE_AND_SPATIAL = 5;

    /**
     * The number of entropy modes.
     */
    private static final int NUM_ENTROPY_IX = 6;

    /**
     * The palette ordering that does not apply.
     */
    private static final int UNUSED_PALETTE = 3;

    /**
     * The largest number of entropy-image tiles.
     */
    private static final int MAX_HUFF_IMAGE_SIZE = 2600;

    /**
     * The smallest entropy-image tile width in bits.
     */
    private static final int MIN_HUFFMAN_BITS = 2;

    /**
     * The largest entropy-image tile width in bits.
     */
    private static final int MAX_HUFFMAN_BITS = MIN_HUFFMAN_BITS + (1 << 3) - 1;

    /**
     * The largest number of predictor-image tiles.
     */
    private static final int MAX_PREDICTOR_IMAGE_SIZE = 1 << 14;

    /**
     * The order in which code-length code lengths are stored.
     */
    private static final int[] STORAGE_ORDER = {17, 18, 0, 1, 2, 3, 4, 5, 16, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};

    /**
     * The number of code-length codes.
     */
    private static final int CODE_LENGTH_CODES = 19;

    /**
     * The encoder quality.
     */
    private final int quality;

    /**
     * The encoder method.
     */
    private final int method;

    /**
     * Whether the fastest method is used.
     */
    private final boolean lowEffort;

    /**
     * Whether transparent pixels keep their color.
     */
    private final boolean exact;

    /**
     * The scratch parses.
     */
    private final BackwardRefs[] refs = new BackwardRefs[4];

    /**
     * The hash chain of the main image.
     */
    private HashChain hashChain;

    /**
     * Scratch entropy statistics.
     */
    private final EntropyMath.Stats stats = new EntropyMath.Stats();

    /**
     * Constructs an encoder.
     *
     * @param quality the quality, from 0 to 100
     * @param method  the method, from 0 to 6
     * @param exact   whether transparent pixels keep their color
     */
    private Vp8lEncoder(int quality, int method, boolean exact) {
        this.quality = quality;
        this.method = method;
        this.lowEffort = method == 0;
        this.exact = exact;
        for (var i = 0; i < 4; i++) {
            refs[i] = new BackwardRefs(256);
        }
    }

    /**
     * Encodes an image as a complete VP8L chunk payload: signature, header and stream.
     *
     * @param argb     the pixels, {@code width * height} packed ARGB values; not modified
     * @param width    the width, from 1 to 16384
     * @param height   the height, from 1 to 16384
     * @param quality  the quality, from 0 to 100
     * @param method   the method, from 0 to 6
     * @param exact    whether transparent pixels keep their color
     * @return the payload
     */
    public static byte[] encodeImage(int[] argb, int width, int height, int quality, int method, boolean exact) {
        var bw = new Vp8lBitWriter(width * height / 2 + 64);
        bw.putBits(width - 1, 14);
        bw.putBits(height - 1, 14);
        var hasAlpha = false;
        for (var i = 0; i < width * height; i++) {
            if ((argb[i] >>> 24) != 0xFF) {
                hasAlpha = true;
                break;
            }
        }
        bw.putBits(hasAlpha ? 1 : 0, 1);
        bw.putBits(0, 3);
        new Vp8lEncoder(quality, method, exact).encodeStream(argb, width, height, bw);
        var stream = bw.toByteArray();
        var payload = new byte[stream.length + 1];
        payload[0] = (byte) Vp8lDecoder.SIGNATURE;
        System.arraycopy(stream, 0, payload, 1, stream.length);
        return payload;
    }

    /**
     * Encodes an image as a headerless VP8L stream, as the alpha chunk stores it.
     *
     * @param argb     the pixels; not modified
     * @param width    the width
     * @param height   the height
     * @param quality  the quality, from 0 to 100
     * @param method   the method, from 0 to 6
     * @param exact    whether transparent pixels keep their color
     * @return the stream
     */
    public static byte[] encodeStream(int[] argb, int width, int height, int quality, int method, boolean exact) {
        var bw = new Vp8lBitWriter(width * height / 4 + 64);
        new Vp8lEncoder(quality, method, exact).encodeStream(argb, width, height, bw);
        return bw.toByteArray();
    }

    /**
     * Holds one candidate configuration.
     *
     * @param entropyIdx     the entropy mode
     * @param paletteSorting the palette ordering
     * @param lz77           the parsers of each sub-configuration
     * @param doNoCache      whether each sub-configuration also tries no color cache
     */
    private record CrunchConfig(int entropyIdx, int paletteSorting, int[] lz77, boolean doNoCache) {
    }

    /**
     * Encodes the transforms and the image, keeping the smallest of the candidate
     * configurations.
     *
     * @param picture the pixels
     * @param width   the width
     * @param height  the height
     * @param bw      the writer, receiving the stream after its current content
     */
    private void encodeStream(int[] picture, int width, int height, Vp8lBitWriter bw) {
        var paletteSorted = new int[Palette.MAX_PALETTE_SIZE];
        var paletteSize = Palette.colorPalette(picture, width, height, paletteSorted);
        var usePalette = paletteSize <= Palette.MAX_PALETTE_SIZE;
        if (!usePalette) {
            paletteSize = 0;
        }
        var histoBits = clampBits(width, height, (usePalette ? 9 : 7) - method, MIN_HUFFMAN_BITS, MAX_HUFFMAN_BITS, MAX_HUFF_IMAGE_SIZE);
        var maxTransformBits = method < 4 ? 6 : method > 4 ? 4 : 5;
        var transformBits = Math.min(histoBits, maxTransformBits);
        var redAndBlueAlwaysZero = new boolean[1];
        var configs = analyze(picture, width, height, usePalette, paletteSize, transformBits, redAndBlueAlwaysZero);
        hashChain = new HashChain(width * height);
        var init = bw.mark();
        var best = new Vp8lBitWriter(64);
        if (configs.size() > 1) {
            best.copyFrom(bw);
        }
        var bestSize = Long.MAX_VALUE;
        var palette = new int[Palette.MAX_PALETTE_SIZE];
        for (var config : configs) {
            var entropyIdx = config.entropyIdx();
            var useThisPalette = entropyIdx == PALETTE || entropyIdx == PALETTE_AND_SPATIAL;
            var useSubtractGreen = entropyIdx == SUB_GREEN || entropyIdx == SPATIAL_SUB_GREEN;
            var usePredict = entropyIdx == SPATIAL || entropyIdx == SPATIAL_SUB_GREEN || entropyIdx == PALETTE_AND_SPATIAL;
            var useCrossColor = !lowEffort && !useThisPalette && !redAndBlueAlwaysZero[0] && usePredict;
            var cacheBits = 0;
            refs[0].clear();
            refs[1].clear();
            int[] argb;
            int currentWidth;
            if (useThisPalette) {
                Palette.sort(config.paletteSorting(), picture, width, height, paletteSorted, paletteSize, palette);
                encodePalette(bw, palette, paletteSize);
                var xbits = paletteSize <= 4 ? (paletteSize <= 2 ? 3 : 2) : (paletteSize <= 16 ? 1 : 0);
                currentWidth = Vp8lDecoder.subSampleSize(width, xbits);
                argb = new int[currentWidth * height];
                Palette.apply(picture, width, height, argb, palette, paletteSize, xbits);
                if (paletteSize < (1 << BackwardReferences.MAX_COLOR_CACHE_BITS)) {
                    cacheBits = EntropyMath.bitsLog2Floor(paletteSize) + 1;
                }
            } else {
                argb = picture.clone();
                currentWidth = width;
            }
            if (useSubtractGreen) {
                bw.putBits(1, 1);
                bw.putBits(2, 2);
                LosslessDsp.INSTANCE.subtractGreenFromBlueAndRed(argb, 0, currentWidth * height);
            }
            if (usePredict) {
                var maxBits = clampBits(currentWidth, height, transformBits, TransformEncoder.MIN_TRANSFORM_BITS,
                        TransformEncoder.MAX_TRANSFORM_BITS, MAX_PREDICTOR_IMAGE_SIZE);
                var minBits = clampBits(currentWidth, height, maxBits - 2 * (method > 4 ? method - 4 : 0),
                        TransformEncoder.MIN_TRANSFORM_BITS, TransformEncoder.MAX_TRANSFORM_BITS, MAX_PREDICTOR_IMAGE_SIZE);
                var transformData = new int[Vp8lDecoder.subSampleSize(currentWidth, TransformEncoder.MIN_TRANSFORM_BITS)
                        * Vp8lDecoder.subSampleSize(height, TransformEncoder.MIN_TRANSFORM_BITS)];
                var bits = TransformEncoder.residualImage(currentWidth, height, minBits, maxBits, lowEffort, argb,
                        transformData, exact);
                bw.putBits(1, 1);
                bw.putBits(0, 2);
                bw.putBits(bits - 2, 3);
                encodeImageNoHuffman(bw, transformData, hashChain, new BackwardRefs[]{refs[0], refs[1]},
                        Vp8lDecoder.subSampleSize(currentWidth, bits), Vp8lDecoder.subSampleSize(height, bits), quality);
            }
            if (useCrossColor) {
                var transformData = new int[Vp8lDecoder.subSampleSize(currentWidth, TransformEncoder.MIN_TRANSFORM_BITS)
                        * Vp8lDecoder.subSampleSize(height, TransformEncoder.MIN_TRANSFORM_BITS)];
                var bits = TransformEncoder.colorSpaceTransform(currentWidth, height, transformBits, quality, argb, transformData);
                bw.putBits(1, 1);
                bw.putBits(1, 2);
                bw.putBits(bits - 2, 3);
                encodeImageNoHuffman(bw, transformData, hashChain, new BackwardRefs[]{refs[0], refs[1]},
                        Vp8lDecoder.subSampleSize(currentWidth, bits), Vp8lDecoder.subSampleSize(height, bits), quality);
            }
            bw.putBits(0, 1);
            encodeImageInternal(bw, argb, currentWidth, height, config, cacheBits, histoBits);
            if (bw.numBytes() < bestSize) {
                bestSize = bw.numBytes();
                bw.swap(best);
            }
            if (configs.size() > 1) {
                bw.reset(init);
            }
        }
        best.swap(bw);
    }

    /**
     * Clamps a tile size and grows it until the tile image is small enough.
     *
     * @param width        the image width
     * @param height       the image height
     * @param bits         the requested tile size in bits
     * @param minBits      the smallest tile size
     * @param maxBits      the largest tile size
     * @param imageSizeMax the largest number of tiles
     * @return the tile size in bits
     */
    static int clampBits(int width, int height, int bits, int minBits, int maxBits, int imageSizeMax) {
        bits = Math.max(minBits, Math.min(maxBits, bits));
        var imageSize = (long) Vp8lDecoder.subSampleSize(width, bits) * Vp8lDecoder.subSampleSize(height, bits);
        while (bits < maxBits && imageSize > imageSizeMax) {
            ++bits;
            imageSize = (long) Vp8lDecoder.subSampleSize(width, bits) * Vp8lDecoder.subSampleSize(height, bits);
        }
        while (bits > minBits && imageSize == 1) {
            imageSize = (long) Vp8lDecoder.subSampleSize(width, bits - 1) * Vp8lDecoder.subSampleSize(height, bits - 1);
            if (imageSize != 1) {
                break;
            }
            --bits;
        }
        return bits;
    }

    /**
     * Chooses the candidate configurations of the method.
     *
     * @param argb                 the pixels
     * @param width                the width
     * @param height               the height
     * @param usePalette           whether the image fits a palette
     * @param paletteSize          the palette size, or {@code 0}
     * @param transformBits        the transform tile size in bits
     * @param redAndBlueAlwaysZero the destination of whether the chosen mode's red and blue
     *                             residuals are all zero
     * @return the configurations
     */
    private List<CrunchConfig> analyze(int[] argb, int width, int height, boolean usePalette, int paletteSize,
                                       int transformBits, boolean[] redAndBlueAlwaysZero) {
        var configs = new ArrayList<CrunchConfig>();
        int nLz77s;
        var doNoCache = false;
        if (lowEffort) {
            configs.add(new CrunchConfig(usePalette ? PALETTE : SPATIAL_SUB_GREEN, usePalette ? Palette.SORTED_DEFAULT : UNUSED_PALETTE, null, false));
            nLz77s = 1;
        } else {
            nLz77s = paletteSize > 0 && paletteSize <= 16 ? 2 : 1;
            var minEntropyIx = analyzeEntropy(argb, width, height, usePalette, paletteSize, transformBits, redAndBlueAlwaysZero);
            if (method == 6 && quality == 100) {
                doNoCache = true;
                for (var i = 0; i < NUM_ENTROPY_IX; i++) {
                    if ((i != PALETTE && i != PALETTE_AND_SPATIAL) || usePalette) {
                        if (usePalette && (i == PALETTE || i == PALETTE_AND_SPATIAL)) {
                            configs.add(new CrunchConfig(i, Palette.MINIMIZE_DELTA, null, false));
                            configs.add(new CrunchConfig(i, Palette.MODIFIED_ZENG, null, false));
                        } else {
                            configs.add(new CrunchConfig(i, UNUSED_PALETTE, null, false));
                        }
                    }
                }
            } else {
                configs.add(new CrunchConfig(minEntropyIx, usePalette ? Palette.MINIMIZE_DELTA : UNUSED_PALETTE, null, false));
                if (quality >= 75 && method == 5) {
                    doNoCache = true;
                    if (minEntropyIx == PALETTE) {
                        configs.add(new CrunchConfig(PALETTE_AND_SPATIAL, Palette.MINIMIZE_DELTA, null, false));
                    }
                }
            }
        }
        var lz77 = new int[nLz77s];
        for (var j = 0; j < nLz77s; j++) {
            lz77[j] = j == 0 ? BackwardReferences.LZ77_STANDARD | BackwardReferences.LZ77_RLE : BackwardReferences.LZ77_BOX;
        }
        var result = new ArrayList<CrunchConfig>(configs.size());
        for (var config : configs) {
            result.add(new CrunchConfig(config.entropyIdx(), config.paletteSorting(), lz77, doNoCache));
        }
        return result;
    }

    /**
     * Hashes a pixel for the palette entropy estimate.
     *
     * @param pix the pixel
     * @return the 8-bit hash
     */
    private static int hashPix(int pix) {
        return (int) ((((pix & 0xFFFFFFFFL) + (pix >>> 19)) * 0x39C5FBA7L & 0xFFFFFFFFL) >>> 24);
    }

    /**
     * Estimates the entropy of the image under each transform combination and returns the best.
     *
     * @param argb                 the pixels
     * @param width                the width
     * @param height               the height
     * @param usePalette           whether the image fits a palette
     * @param paletteSize          the palette size
     * @param transformBits        the transform tile size in bits
     * @param redAndBlueAlwaysZero the destination of whether the best mode's red and blue values
     *                             are all zero
     * @return the best entropy mode
     */
    private static int analyzeEntropy(int[] argb, int width, int height, boolean usePalette, int paletteSize,
                                      int transformBits, boolean[] redAndBlueAlwaysZero) {
        if (usePalette && paletteSize <= 16) {
            redAndBlueAlwaysZero[0] = true;
            return PALETTE;
        }
        var histo = new int[13 * 256];
        var pixPrev = argb[0];
        for (var y = 0; y < height; y++) {
            var row = y * width;
            for (var x = 0; x < width; x++) {
                var pix = argb[row + x];
                var pixDiff = ScalarLosslessDsp.subPixels(pix, pixPrev);
                pixPrev = pix;
                if (pixDiff == 0 || (y > 0 && pix == argb[row - width + x])) {
                    continue;
                }
                ++histo[(pix >>> 24)];
                ++histo[4 * 256 + ((pix >>> 16) & 0xFF)];
                ++histo[2 * 256 + ((pix >>> 8) & 0xFF)];
                ++histo[6 * 256 + (pix & 0xFF)];
                ++histo[256 + (pixDiff >>> 24)];
                ++histo[5 * 256 + ((pixDiff >>> 16) & 0xFF)];
                ++histo[3 * 256 + ((pixDiff >>> 8) & 0xFF)];
                ++histo[7 * 256 + (pixDiff & 0xFF)];
                var green = pix >> 8;
                ++histo[8 * 256 + (((pix >> 16) - green) & 0xFF)];
                ++histo[10 * 256 + ((pix - green) & 0xFF)];
                var greenDiff = pixDiff >> 8;
                ++histo[9 * 256 + (((pixDiff >> 16) - greenDiff) & 0xFF)];
                ++histo[11 * 256 + ((pixDiff - greenDiff) & 0xFF)];
                ++histo[12 * 256 + hashPix(pix)];
            }
        }
        ++histo[9 * 256];
        ++histo[11 * 256];
        ++histo[5 * 256];
        ++histo[3 * 256];
        ++histo[7 * 256];
        ++histo[256];
        var comp = new long[13];
        for (var j = 0; j < 13; j++) {
            comp[j] = EntropyMath.bitsEntropy(histo, j * 256, 256);
        }
        var entropy = new long[NUM_ENTROPY_IX];
        entropy[DIRECT] = comp[0] + comp[4] + comp[2] + comp[6];
        entropy[SPATIAL] = comp[1] + comp[5] + comp[3] + comp[7];
        entropy[SUB_GREEN] = comp[0] + comp[8] + comp[2] + comp[10];
        entropy[SPATIAL_SUB_GREEN] = comp[1] + comp[9] + comp[3] + comp[11];
        entropy[PALETTE] = comp[12];
        long tiles = (long) Vp8lDecoder.subSampleSize(width, transformBits) * Vp8lDecoder.subSampleSize(height, transformBits);
        entropy[SPATIAL] += tiles * EntropyMath.fastLog2(14);
        entropy[SPATIAL_SUB_GREEN] += tiles * EntropyMath.fastLog2(24);
        entropy[PALETTE] += ((long) paletteSize * 8) << EntropyMath.LOG_2_PRECISION_BITS;
        var lastMode = usePalette ? PALETTE : SPATIAL_SUB_GREEN;
        var minIx = DIRECT;
        for (var k = DIRECT + 1; k <= lastMode; k++) {
            if (Long.compareUnsigned(entropy[minIx], entropy[k]) > 0) {
                minIx = k;
            }
        }
        int[][] pairs = {{4, 6}, {5, 7}, {8, 10}, {9, 11}, {4, 6}};
        var redOff = pairs[minIx][0] * 256;
        var blueOff = pairs[minIx][1] * 256;
        redAndBlueAlwaysZero[0] = true;
        for (var i = 1; i < 256; i++) {
            if ((histo[redOff + i] | histo[blueOff + i]) != 0) {
                redAndBlueAlwaysZero[0] = false;
                break;
            }
        }
        return minIx;
    }

    /**
     * Writes the color indexing transform with its delta-coded palette.
     *
     * @param bw          the writer
     * @param palette     the ordered palette
     * @param paletteSize the palette size
     */
    private void encodePalette(Vp8lBitWriter bw, int[] palette, int paletteSize) {
        var encodedSize = palette[paletteSize - 1] == 0 && paletteSize > 17 ? paletteSize - 1 : paletteSize;
        bw.putBits(1, 1);
        bw.putBits(3, 2);
        bw.putBits(encodedSize - 1, 8);
        var tmp = new int[encodedSize];
        for (var i = encodedSize - 1; i >= 1; --i) {
            tmp[i] = ScalarLosslessDsp.subPixels(palette[i], palette[i - 1]);
        }
        tmp[0] = palette[0];
        encodeImageNoHuffman(bw, tmp, hashChain, new BackwardRefs[]{refs[0], refs[1]}, encodedSize, 1, 20);
    }

    /**
     * Encodes a sub-image (transform data, palette or entropy image) with a single prefix code
     * group and no color cache.
     *
     * @param bw      the writer
     * @param argb    the sub-image
     * @param chain   a hash chain large enough for the sub-image
     * @param refs    two scratch parses
     * @param width   the sub-image width
     * @param height  the sub-image height
     * @param quality the quality
     */
    private void encodeImageNoHuffman(Vp8lBitWriter bw, int[] argb, HashChain chain, BackwardRefs[] refs,
                                      int width, int height, int quality) {
        chain.fill(quality, argb, width, height, lowEffort);
        BackwardReferences.getBackwardReferences(width, height, argb, quality, false,
                BackwardReferences.LZ77_STANDARD | BackwardReferences.LZ77_RLE, 0, false, chain, refs, stats);
        var histo = new Histogram(0);
        histo.storeRefs(refs[0], 0, false);
        var codes = huffmanCodes(new Histogram[]{histo}, 1);
        bw.putBits(0, 1);
        for (var i = 0; i < 5; i++) {
            storeHuffmanCode(bw, codes[i]);
            clearIfOnlyOneSymbol(codes[i]);
        }
        storeImageToBitMask(bw, width, 0, refs[0], new int[]{0}, codes);
    }

    /**
     * Encodes the main image with its color cache, entropy image and prefix codes, trying the
     * sub-configurations and keeping the smallest.
     *
     * @param bw            the writer
     * @param argb          the transformed image
     * @param width         the transformed width
     * @param height        the height
     * @param config        the configuration
     * @param cacheBits     the color cache bound set by the palette, or {@code 0}
     * @param histogramBits the entropy-image tile size in bits
     */
    private void encodeImageInternal(Vp8lBitWriter bw, int[] argb, int width, int height, CrunchConfig config,
                                     int cacheBits, int histogramBits) {
        var xysize = Vp8lDecoder.subSampleSize(width, histogramBits) * Vp8lDecoder.subSampleSize(height, histogramBits);
        var histogramArgb = new int[xysize];
        var chainHistogram = new HashChain(xysize);
        var init = bw.mark();
        var best = new Vp8lBitWriter(64);
        var bestSize = Long.MAX_VALUE;
        hashChain.fill(quality, argb, width, height, lowEffort);
        var cacheBitsInit = cacheBits == 0 ? BackwardReferences.MAX_COLOR_CACHE_BITS : cacheBits;
        if (config.lz77().length > 1 || config.doNoCache()) {
            best.copyFrom(bw);
        }
        for (var lz77 : config.lz77()) {
            var cacheBitsBest = BackwardReferences.getBackwardReferences(width, height, argb, quality, lowEffort, lz77,
                    cacheBitsInit, config.doNoCache(), hashChain, refs, stats);
            for (var iCache = 0; iCache < (config.doNoCache() ? 2 : 1); iCache++) {
                var cacheBitsTmp = iCache == 0 ? cacheBitsBest : 0;
                if (iCache == 1 && cacheBitsBest == 0) {
                    break;
                }
                bw.reset(init);
                var histogramImage = new HistogramImage.HistogramSet(xysize, cacheBitsTmp);
                var tmpHisto = new Histogram(cacheBitsTmp);
                HistogramImage.getSymbols(width, height, refs[iCache], quality, lowEffort, histogramBits, cacheBitsTmp,
                        histogramImage, tmpHisto, histogramArgb, stats);
                var codes = huffmanCodes(histogramImage.histograms, histogramImage.size);
                if (cacheBitsTmp > 0) {
                    bw.putBits(1, 1);
                    bw.putBits(cacheBitsTmp, 4);
                } else {
                    bw.putBits(0, 1);
                }
                var imageSize = 0;
                for (var i = 0; i < xysize; i++) {
                    if (histogramArgb[i] >= imageSize) {
                        imageSize = histogramArgb[i] + 1;
                    }
                    histogramArgb[i] <<= 8;
                }
                var writeHistogramImage = imageSize > 1;
                bw.putBits(writeHistogramImage ? 1 : 0, 1);
                var bits = histogramBits;
                if (writeHistogramImage) {
                    var bestBits = new int[1];
                    TransformEncoder.optimizeSampling(histogramArgb, width, height, histogramBits, MAX_HUFFMAN_BITS, bestBits);
                    bits = bestBits[0];
                    bw.putBits(bits - 2, 3);
                    encodeImageNoHuffman(bw, histogramArgb, chainHistogram, new BackwardRefs[]{refs[2], refs[3]},
                            Vp8lDecoder.subSampleSize(width, bits), Vp8lDecoder.subSampleSize(height, bits), quality);
                }
                for (var i = 0; i < 5 * imageSize; i++) {
                    storeHuffmanCode(bw, codes[i]);
                    clearIfOnlyOneSymbol(codes[i]);
                }
                storeImageToBitMask(bw, width, bits, refs[iCache], histogramArgb, codes);
                if (bw.numBytes() < bestSize) {
                    bestSize = bw.numBytes();
                    bw.swap(best);
                }
            }
        }
        bw.swap(best);
    }

    /**
     * Builds the five prefix codes of every histogram.
     *
     * @param histograms the histograms; their counts are smoothed in place
     * @param count      the number of histograms
     * @return the codes, five per histogram
     */
    private static HuffmanEncoder.Code[] huffmanCodes(Histogram[] histograms, int count) {
        var codes = new HuffmanEncoder.Code[5 * count];
        for (var i = 0; i < count; i++) {
            var histo = histograms[i];
            for (var k = 0; k < 5; k++) {
                var numSymbols = k == 0 ? Histogram.numCodes(histo.paletteCodeBits) : k == 4 ? Histogram.NUM_DISTANCE_CODES : 256;
                var code = new HuffmanEncoder.Code(numSymbols);
                HuffmanEncoder.createTree(histo.population(k), 15, code);
                codes[5 * i + k] = code;
            }
        }
        return codes;
    }

    /**
     * Writes a prefix code: the simple one- or two-symbol form when possible, the code-length
     * form otherwise.
     *
     * @param bw   the writer
     * @param code the code
     */
    private static void storeHuffmanCode(Vp8lBitWriter bw, HuffmanEncoder.Code code) {
        var count = 0;
        var symbol0 = 0;
        var symbol1 = 0;
        for (var i = 0; i < code.numSymbols && count < 3; i++) {
            if (code.codeLengths[i] != 0) {
                if (count == 0) {
                    symbol0 = i;
                } else if (count == 1) {
                    symbol1 = i;
                }
                ++count;
            }
        }
        if (count == 0) {
            bw.putBits(0x01, 4);
        } else if (count <= 2 && symbol0 < 256 && symbol1 < 256) {
            bw.putBits(1, 1);
            bw.putBits(count - 1, 1);
            if (symbol0 <= 1) {
                bw.putBits(0, 1);
                bw.putBits(symbol0, 1);
            } else {
                bw.putBits(1, 1);
                bw.putBits(symbol0, 8);
            }
            if (count == 2) {
                bw.putBits(symbol1, 8);
            }
        } else {
            storeFullHuffmanCode(bw, code);
        }
    }

    /**
     * Writes a prefix code in the code-length form.
     *
     * @param bw   the writer
     * @param tree the code
     */
    private static void storeFullHuffmanCode(Vp8lBitWriter bw, HuffmanEncoder.Code tree) {
        var tokenCode = new int[tree.numSymbols];
        var tokenExtra = new int[tree.numSymbols];
        bw.putBits(0, 1);
        var numTokens = HuffmanEncoder.compressTree(tree, tokenCode, tokenExtra);
        var histogram = new int[CODE_LENGTH_CODES];
        for (var i = 0; i < numTokens; i++) {
            ++histogram[tokenCode[i]];
        }
        var lengthCode = new HuffmanEncoder.Code(CODE_LENGTH_CODES);
        HuffmanEncoder.createTree(histogram, 7, lengthCode);
        var codesToStore = CODE_LENGTH_CODES;
        for (; codesToStore > 4; --codesToStore) {
            if (lengthCode.codeLengths[STORAGE_ORDER[codesToStore - 1]] != 0) {
                break;
            }
        }
        bw.putBits(codesToStore - 4, 4);
        for (var i = 0; i < codesToStore; i++) {
            bw.putBits(lengthCode.codeLengths[STORAGE_ORDER[i]], 3);
        }
        clearIfOnlyOneSymbol(lengthCode);
        var trailingZeroBits = 0;
        var trimmedLength = numTokens;
        var i = numTokens;
        while (i-- > 0) {
            var ix = tokenCode[i];
            if (ix == 0 || ix == 17 || ix == 18) {
                --trimmedLength;
                trailingZeroBits += lengthCode.codeLengths[ix];
                if (ix == 17) {
                    trailingZeroBits += 3;
                } else if (ix == 18) {
                    trailingZeroBits += 7;
                }
            } else {
                break;
            }
        }
        var writeTrimmed = trimmedLength > 1 && trailingZeroBits > 12;
        var length = writeTrimmed ? trimmedLength : numTokens;
        bw.putBits(writeTrimmed ? 1 : 0, 1);
        if (writeTrimmed) {
            if (trimmedLength == 2) {
                bw.putBits(0, 3 + 2);
            } else {
                var nbits = EntropyMath.bitsLog2Floor(trimmedLength - 2);
                var nbitpairs = nbits / 2 + 1;
                bw.putBits(nbitpairs - 1, 3);
                bw.putBits(trimmedLength - 2, nbitpairs * 2);
            }
        }
        for (var t = 0; t < length; t++) {
            var ix = tokenCode[t];
            bw.putBits(lengthCode.codes[ix], lengthCode.codeLengths[ix]);
            switch (ix) {
                case 16 -> bw.putBits(tokenExtra[t], 2);
                case 17 -> bw.putBits(tokenExtra[t], 3);
                case 18 -> bw.putBits(tokenExtra[t], 7);
                default -> {
                }
            }
        }
    }

    /**
     * Clears a prefix code that has a single symbol, which is then coded with zero bits.
     *
     * @param code the code
     */
    private static void clearIfOnlyOneSymbol(HuffmanEncoder.Code code) {
        var count = 0;
        for (var k = 0; k < code.numSymbols; k++) {
            if (code.codeLengths[k] != 0) {
                ++count;
                if (count > 1) {
                    return;
                }
            }
        }
        java.util.Arrays.fill(code.codeLengths, 0);
        java.util.Arrays.fill(code.codes, 0);
    }

    /**
     * Writes the LZ77 symbols of an image with the prefix codes of their tiles.
     *
     * @param bw            the writer
     * @param width         the image width
     * @param histoBits     the entropy-image tile size in bits, or {@code 0} for one group
     * @param refs          the symbols
     * @param symbols       the entropy image (group index in bits 8-23)
     * @param codes         the prefix codes, five per group
     */
    private static void storeImageToBitMask(Vp8lBitWriter bw, int width, int histoBits, BackwardRefs refs, int[] symbols,
                                            HuffmanEncoder.Code[] codes) {
        var histoXSize = histoBits != 0 ? Vp8lDecoder.subSampleSize(width, histoBits) : 1;
        var tileMask = histoBits == 0 ? 0 : -(1 << histoBits);
        var x = 0;
        var y = 0;
        var tileX = 0;
        var tileY = 0;
        var group = 5 * ((symbols[0] >>> 8) & 0xFFFF);
        var data = refs.data;
        for (var r = 0; r < refs.size; r++) {
            var v = data[r];
            if (tileX != (x & tileMask) || tileY != (y & tileMask)) {
                tileX = x & tileMask;
                tileY = y & tileMask;
                group = 5 * ((symbols[(y >> histoBits) * histoXSize + (x >> histoBits)] >>> 8) & 0xFFFF);
            }
            switch (BackwardRefs.mode(v)) {
                case BackwardRefs.LITERAL -> {
                    var argb = BackwardRefs.value(v);
                    writeCode(bw, codes[group], (argb >>> 8) & 0xFF);
                    writeCode(bw, codes[group + 1], (argb >>> 16) & 0xFF);
                    writeCode(bw, codes[group + 2], argb & 0xFF);
                    writeCode(bw, codes[group + 3], argb >>> 24);
                }
                case BackwardRefs.CACHE_IDX -> writeCode(bw, codes[group], 256 + 24 + BackwardRefs.value(v));
                default -> {
                    var length = BackwardRefs.length(v);
                    var lengthCode = codes[group];
                    var index = 256 + Prefix.code(length);
                    var depth = lengthCode.codeLengths[index];
                    bw.putBits((Prefix.extraValue(length) << depth) | lengthCode.codes[index], depth + Prefix.extraBits(length));
                    var distance = BackwardRefs.value(v);
                    writeCode(bw, codes[group + 4], Prefix.code(distance));
                    bw.putBits(Prefix.extraValue(distance), Prefix.extraBits(distance));
                }
            }
            x += BackwardRefs.length(v);
            while (x >= width) {
                x -= width;
                ++y;
            }
        }
    }

    /**
     * Writes one symbol with a prefix code.
     *
     * @param bw     the writer
     * @param code   the prefix code
     * @param symbol the symbol
     */
    private static void writeCode(Vp8lBitWriter bw, HuffmanEncoder.Code code, int symbol) {
        bw.putBits(code.codes[symbol], code.codeLengths[symbol]);
    }
}
