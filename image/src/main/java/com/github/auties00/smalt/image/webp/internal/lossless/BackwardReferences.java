package com.github.auties00.smalt.image.webp.internal.lossless;

import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Parses an image into LZ77 symbols and chooses the color cache size.
 *
 * <p>Three parsers are available: run-length (copies from the previous pixel or the row above),
 * standard LZ77 over the hash chain with one step of look-ahead, and a "box" LZ77 restricted to
 * offsets in a small window around the pixel. Their results are compared by estimated entropy;
 * at quality 25 and above the best LZ77 parse is then refined by a shortest-path search over a
 * cost model ({@link TraceBackwards}). Finally distances are replaced by plane codes.
 *
 * @implNote This implementation follows libwebp's {@code src/enc/backward_references_enc.c}
 * (BSD-3).
 */
final class BackwardReferences {
    /**
     * The standard LZ77 parser.
     */
    static final int LZ77_STANDARD = 1;

    /**
     * The run-length parser.
     */
    static final int LZ77_RLE = 2;

    /**
     * The box-window LZ77 parser.
     */
    static final int LZ77_BOX = 4;

    /**
     * The largest color cache width tried.
     */
    static final int MAX_COLOR_CACHE_BITS = 10;

    /**
     * The shortest copy emitted.
     */
    private static final int MIN_LENGTH = 4;

    /**
     * The number of window offsets examined by the box parser.
     */
    private static final int WINDOW_OFFSETS_SIZE_MAX = 32;

    /**
     * Prevents instantiation.
     */
    private BackwardReferences() {
        throw new AssertionError();
    }

    /**
     * Appends a literal, or a cache index when the cache holds the pixel.
     *
     * @param pixel the pixel
     * @param cache the color cache, or {@code null}
     * @param refs  the destination
     */
    private static void addSingleLiteral(int pixel, ColorCache cache, BackwardRefs refs) {
        if (cache != null) {
            var key = cache.index(pixel);
            if (cache.colors[key] == pixel) {
                refs.add(BackwardRefs.cacheIdx(key));
            } else {
                refs.add(BackwardRefs.literal(pixel));
                cache.colors[key] = pixel;
            }
        } else {
            refs.add(BackwardRefs.literal(pixel));
        }
    }

    /**
     * Parses with run-length copies only.
     *
     * @param xsize     the image width
     * @param ysize     the image height
     * @param argb      the pixels
     * @param cacheBits the color cache width, or {@code 0}
     * @param refs      the destination
     */
    static void rle(int xsize, int ysize, int[] argb, int cacheBits, BackwardRefs refs) {
        var pixCount = xsize * ysize;
        var cache = cacheBits > 0 ? new ColorCache(cacheBits) : null;
        refs.clear();
        addSingleLiteral(argb[0], cache, refs);
        var i = 1;
        while (i < pixCount) {
            var maxLen = Math.min(pixCount - i, HashChain.MAX_LENGTH);
            var rleLen = HashChain.findMatchLength(argb, i, i - 1, 0, maxLen);
            var prevRowLen = i < xsize ? 0 : HashChain.findMatchLength(argb, i, i - xsize, 0, maxLen);
            if (rleLen >= prevRowLen && rleLen >= MIN_LENGTH) {
                refs.add(BackwardRefs.copy(1, rleLen));
                i += rleLen;
            } else if (prevRowLen >= MIN_LENGTH) {
                refs.add(BackwardRefs.copy(xsize, prevRowLen));
                if (cache != null) {
                    for (var k = 0; k < prevRowLen; k++) {
                        cache.insert(argb[i + k]);
                    }
                }
                i += prevRowLen;
            } else {
                addSingleLiteral(argb[i], cache, refs);
                i++;
            }
        }
    }

    /**
     * Parses with the matches of a hash chain, looking one match ahead.
     *
     * @param xsize     the image width
     * @param ysize     the image height
     * @param argb      the pixels
     * @param cacheBits the color cache width, or {@code 0}
     * @param chain     the best match of every pixel
     * @param refs      the destination
     */
    static void lz77(int xsize, int ysize, int[] argb, int cacheBits, HashChain chain, BackwardRefs refs) {
        var pixCount = xsize * ysize;
        var cache = cacheBits > 0 ? new ColorCache(cacheBits) : null;
        var iLastCheck = -1;
        refs.clear();
        for (var i = 0; i < pixCount; ) {
            var offset = chain.offset(i);
            var len = chain.length(i);
            if (len >= MIN_LENGTH) {
                var lenIni = len;
                var maxReach = 0;
                var jMax = i + lenIni >= pixCount ? pixCount - 1 : i + lenIni;
                iLastCheck = Math.max(i, iLastCheck);
                for (var j = iLastCheck + 1; j <= jMax; j++) {
                    var lenJ = chain.length(j);
                    var reach = j + (lenJ >= MIN_LENGTH ? lenJ : 1);
                    if (reach > maxReach) {
                        len = j - i;
                        maxReach = reach;
                        if (maxReach >= pixCount) {
                            break;
                        }
                    }
                }
            } else {
                len = 1;
            }
            if (len == 1) {
                addSingleLiteral(argb[i], cache, refs);
            } else {
                refs.add(BackwardRefs.copy(offset, len));
                if (cache != null) {
                    for (var j = i; j < i + len; j++) {
                        cache.insert(argb[j]);
                    }
                }
            }
            i += len;
        }
    }

    /**
     * Parses with matches restricted to a window of offsets around each pixel.
     *
     * @param xsize     the image width
     * @param ysize     the image height
     * @param argb      the pixels
     * @param cacheBits the color cache width, or {@code 0}
     * @param chainBest the unrestricted best matches
     * @param chain     scratch chain receiving the window matches
     * @param refs      the destination
     */
    static void lz77Box(int xsize, int ysize, int[] argb, int cacheBits, HashChain chainBest, HashChain chain, BackwardRefs refs) {
        var pixCount = xsize * ysize;
        var counts = new int[pixCount];
        var windowOffsets = new int[WINDOW_OFFSETS_SIZE_MAX];
        var windowOffsetsNew = new int[WINDOW_OFFSETS_SIZE_MAX];
        var windowOffsetsSize = 0;
        var windowOffsetsNewSize = 0;
        var bestOffsetPrev = -1;
        var bestLengthPrev = -1;
        var i = pixCount - 2;
        counts[i + 1] = 1;
        for (; i >= 0; --i) {
            if (argb[i] == argb[i + 1]) {
                counts[i] = counts[i + 1] + (counts[i + 1] != HashChain.MAX_LENGTH ? 1 : 0);
            } else {
                counts[i] = 1;
            }
        }
        for (var y = 0; y <= 6; y++) {
            for (var x = -6; x <= 6; x++) {
                var offset = y * xsize + x;
                if (offset <= 0) {
                    continue;
                }
                var planeCode = Prefix.distanceToPlaneCode(xsize, offset) - 1;
                if (planeCode >= WINDOW_OFFSETS_SIZE_MAX) {
                    continue;
                }
                windowOffsets[planeCode] = offset;
            }
        }
        for (i = 0; i < WINDOW_OFFSETS_SIZE_MAX; i++) {
            if (windowOffsets[i] == 0) {
                continue;
            }
            windowOffsets[windowOffsetsSize++] = windowOffsets[i];
        }
        for (i = 0; i < windowOffsetsSize; i++) {
            var reachable = false;
            for (var j = 0; j < windowOffsetsSize && !reachable; j++) {
                reachable = windowOffsets[i] == windowOffsets[j] + 1;
            }
            if (!reachable) {
                windowOffsetsNew[windowOffsetsNewSize++] = windowOffsets[i];
            }
        }
        chain.offsetLength[0] = 0;
        for (i = 1; i < pixCount; i++) {
            var bestLength = chainBest.length(i);
            var bestOffset = 0;
            var doCompute = true;
            if (bestLength >= HashChain.MAX_LENGTH) {
                bestOffset = chainBest.offset(i);
                for (var ind = 0; ind < windowOffsetsSize; ind++) {
                    if (bestOffset == windowOffsets[ind]) {
                        doCompute = false;
                        break;
                    }
                }
            }
            if (doCompute) {
                var usePrev = bestLengthPrev > 1 && bestLengthPrev < HashChain.MAX_LENGTH;
                var numInd = usePrev ? windowOffsetsNewSize : windowOffsetsSize;
                bestLength = usePrev ? bestLengthPrev - 1 : 0;
                bestOffset = usePrev ? bestOffsetPrev : 0;
                for (var ind = 0; ind < numInd; ind++) {
                    var currLength = 0;
                    var j = i;
                    var jOffset = usePrev ? i - windowOffsetsNew[ind] : i - windowOffsets[ind];
                    if (jOffset < 0 || argb[jOffset] != argb[i]) {
                        continue;
                    }
                    do {
                        var countsJOffset = counts[jOffset];
                        var countsJ = counts[j];
                        if (countsJOffset != countsJ) {
                            currLength += Math.min(countsJOffset, countsJ);
                            break;
                        }
                        currLength += countsJOffset;
                        jOffset += countsJOffset;
                        j += countsJOffset;
                    } while (currLength <= HashChain.MAX_LENGTH && j < pixCount && argb[jOffset] == argb[j]);
                    if (bestLength < currLength) {
                        bestOffset = usePrev ? windowOffsetsNew[ind] : windowOffsets[ind];
                        if (currLength >= HashChain.MAX_LENGTH) {
                            bestLength = HashChain.MAX_LENGTH;
                            break;
                        } else {
                            bestLength = currLength;
                        }
                    }
                }
            }
            if (bestLength <= MIN_LENGTH) {
                chain.offsetLength[i] = 0;
                bestOffsetPrev = 0;
                bestLengthPrev = 0;
            } else {
                chain.offsetLength[i] = (bestOffset << HashChain.MAX_LENGTH_BITS) | bestLength;
                bestOffsetPrev = bestOffset;
                bestLengthPrev = bestLength;
            }
        }
        chain.offsetLength[0] = 0;
        lz77(xsize, ysize, argb, cacheBits, chain, refs);
    }

    /**
     * Replaces the distances of copies with plane codes.
     *
     * @param xsize the image width
     * @param refs  the symbols, modified in place
     */
    static void to2dLocality(int xsize, BackwardRefs refs) {
        var data = refs.data;
        for (var i = 0; i < refs.size; i++) {
            var v = data[i];
            if (BackwardRefs.mode(v) == BackwardRefs.COPY) {
                data[i] = BackwardRefs.copy(Prefix.distanceToPlaneCode(xsize, BackwardRefs.value(v)), BackwardRefs.length(v));
            }
        }
    }

    /**
     * The smallest parse, in codes, whose color cache widths are evaluated on several threads.
     */
    private static final int PARALLEL_CACHE_REFS = 1 << 16;

    /**
     * Chooses the color cache width giving the lowest entropy for a parse made without a cache.
     *
     * @param argb          the pixels
     * @param quality       the encoder quality; at 25 and below the cache is disabled
     * @param refs          the parse
     * @param cacheBitsMax  the largest width tried
     * @param stats         scratch statistics
     * @return the best width, possibly {@code 0}
     */
    static int calculateBestCacheSize(int[] argb, int quality, BackwardRefs refs, int cacheBitsMax, EntropyMath.Stats stats) {
        if (quality <= 25) {
            cacheBitsMax = 0;
        }
        if (cacheBitsMax == 0) {
            return 0;
        }
        var histos = new Histogram[cacheBitsMax + 1];
        if (refs.size >= PARALLEL_CACHE_REFS && ParallelBands.enabled() && ParallelBands.available() > 0) {
            var count = cacheBitsMax + 1;
            var bands = Math.min(count, 1 + ParallelBands.available());
            ParallelBands.run(bands, band -> {
                for (var i = ParallelBands.start(band, bands, count); i < ParallelBands.start(band + 1, bands, count); i++) {
                    histos[i] = cacheHistogram(argb, refs, i);
                }
            });
            return bestCacheBits(histos, cacheBitsMax, stats);
        }
        var hashers = new ColorCache[cacheBitsMax + 1];
        for (var i = 0; i <= cacheBitsMax; i++) {
            histos[i] = new Histogram(i);
            if (i > 0) {
                hashers[i] = new ColorCache(i);
            }
        }
        var pos = 0;
        var data = refs.data;
        for (var r = 0; r < refs.size; r++) {
            var v = data[r];
            if (BackwardRefs.mode(v) == BackwardRefs.LITERAL) {
                var pix = argb[pos++];
                var a = pix >>> 24;
                var red = (pix >>> 16) & 0xFF;
                var g = (pix >>> 8) & 0xFF;
                var b = pix & 0xFF;
                var key = ColorCache.hash(pix, 32 - cacheBitsMax);
                ++histos[0].blue[b];
                ++histos[0].literal[g];
                ++histos[0].red[red];
                ++histos[0].alpha[a];
                for (var i = cacheBitsMax; i >= 1; --i, key >>>= 1) {
                    if (hashers[i].colors[key] == pix) {
                        ++histos[i].literal[256 + 24 + key];
                    } else {
                        hashers[i].colors[key] = pix;
                        ++histos[i].blue[b];
                        ++histos[i].literal[g];
                        ++histos[i].red[red];
                        ++histos[i].alpha[a];
                    }
                }
            } else {
                var len = BackwardRefs.length(v);
                var argbPrev = argb[pos] ^ 0xFFFFFFFF;
                var code = Prefix.code(len);
                for (var i = 0; i <= cacheBitsMax; i++) {
                    ++histos[i].literal[256 + code];
                }
                do {
                    if (argb[pos] != argbPrev) {
                        var key = ColorCache.hash(argb[pos], 32 - cacheBitsMax);
                        for (var i = cacheBitsMax; i >= 1; --i, key >>>= 1) {
                            hashers[i].colors[key] = argb[pos];
                        }
                        argbPrev = argb[pos];
                    }
                    pos++;
                } while (--len != 0);
            }
        }
        return bestCacheBits(histos, cacheBitsMax, stats);
    }

    /**
     * Returns the color cache width with the lowest estimated cost.
     *
     * @param histos       the histogram of the parse for every width from {@code 0}
     * @param cacheBitsMax the largest width
     * @param stats        scratch statistics
     * @return the best width, the smallest of equal ones
     */
    private static int bestCacheBits(Histogram[] histos, int cacheBitsMax, EntropyMath.Stats stats) {
        var best = 0;
        var entropyMin = Long.MAX_VALUE;
        for (var i = 0; i <= cacheBitsMax; i++) {
            var entropy = histos[i].estimateBits(stats);
            if (i == 0 || Long.compareUnsigned(entropy, entropyMin) < 0) {
                entropyMin = entropy;
                best = i;
            }
        }
        return best;
    }

    /**
     * Collects the histogram of a parse coded with a color cache of one width.
     *
     * @param argb the pixels
     * @param refs the parse made without a cache
     * @param bits the color cache width, {@code 0} for none
     * @return the histogram
     */
    private static Histogram cacheHistogram(int[] argb, BackwardRefs refs, int bits) {
        var histo = new Histogram(bits);
        var cache = bits > 0 ? new ColorCache(bits).colors : null;
        var shift = 32 - bits;
        var pos = 0;
        var data = refs.data;
        for (var r = 0; r < refs.size; r++) {
            var v = data[r];
            if (BackwardRefs.mode(v) == BackwardRefs.LITERAL) {
                var pix = argb[pos++];
                if (bits > 0) {
                    var key = ColorCache.hash(pix, shift);
                    if (cache[key] == pix) {
                        ++histo.literal[256 + 24 + key];
                        continue;
                    }
                    cache[key] = pix;
                }
                ++histo.blue[pix & 0xFF];
                ++histo.literal[(pix >>> 8) & 0xFF];
                ++histo.red[(pix >>> 16) & 0xFF];
                ++histo.alpha[pix >>> 24];
            } else {
                var len = BackwardRefs.length(v);
                ++histo.literal[256 + Prefix.code(len)];
                if (bits == 0) {
                    pos += len;
                    continue;
                }
                var argbPrev = argb[pos] ^ 0xFFFFFFFF;
                do {
                    if (argb[pos] != argbPrev) {
                        cache[ColorCache.hash(argb[pos], shift)] = argb[pos];
                        argbPrev = argb[pos];
                    }
                    pos++;
                } while (--len != 0);
            }
        }
        return histo;
    }

    /**
     * Rewrites the literals of a cache-less parse to use a color cache.
     *
     * @param argb      the pixels
     * @param cacheBits the color cache width, positive
     * @param refs      the parse, modified in place
     */
    static void withLocalCache(int[] argb, int cacheBits, BackwardRefs refs) {
        var cache = new ColorCache(cacheBits);
        var pixelIndex = 0;
        var data = refs.data;
        for (var r = 0; r < refs.size; r++) {
            var v = data[r];
            if (BackwardRefs.mode(v) == BackwardRefs.LITERAL) {
                var literal = BackwardRefs.value(v);
                var ix = cache.contains(literal);
                if (ix >= 0) {
                    data[r] = BackwardRefs.cacheIdx(ix);
                } else {
                    cache.insert(literal);
                }
                ++pixelIndex;
            } else {
                var len = BackwardRefs.length(v);
                for (var k = 0; k < len; k++) {
                    cache.insert(argb[pixelIndex++]);
                }
            }
        }
    }

    /**
     * Computes the best parse of an image and its color cache width.
     *
     * @param width           the image width
     * @param height          the image height
     * @param argb            the pixels
     * @param quality         the encoder quality, from 0 to 100
     * @param lowEffort       whether to use the fastest parse
     * @param lz77TypesToTry  the parsers to try, a mask of {@link #LZ77_STANDARD},
     *                        {@link #LZ77_RLE} and {@link #LZ77_BOX}
     * @param cacheBitsMax    the largest color cache width tried
     * @param doNoCache       whether to also produce the best parse without a cache in
     *                        {@code refs[1]}
     * @param chain           the hash chain of the image
     * @param refs            scratch and result parses: {@code refs[0]} receives the best parse
     *                        with a cache, {@code refs[1]} the best without
     * @param stats           scratch statistics
     * @return the color cache width of {@code refs[0]}
     */
    static int getBackwardReferences(int width, int height, int[] argb, int quality, boolean lowEffort,
                                     int lz77TypesToTry, int cacheBitsMax, boolean doNoCache, HashChain chain,
                                     BackwardRefs[] refs, EntropyMath.Stats stats) {
        if (lowEffort) {
            lz77(width, height, argb, 0, chain, refs[0]);
            to2dLocality(width, refs[0]);
            return 0;
        }
        var histo = new Histogram(MAX_COLOR_CACHE_BITS);
        var lz77TypesBest = new int[2];
        var bitCostsBest = new long[]{-1L, -1L};
        HashChain chainBox = null;
        var tmpIndex = doNoCache ? 2 : 1;
        var cacheBitsBest = 0;
        for (var lz77Type = 1; lz77TypesToTry != 0; lz77TypesToTry &= ~lz77Type, lz77Type <<= 1) {
            if ((lz77TypesToTry & lz77Type) == 0) {
                continue;
            }
            var refsTmp = refs[tmpIndex];
            switch (lz77Type) {
                case LZ77_RLE -> rle(width, height, argb, 0, refsTmp);
                case LZ77_STANDARD -> lz77(width, height, argb, 0, chain, refsTmp);
                default -> {
                    chainBox = new HashChain(width * height);
                    lz77Box(width, height, argb, 0, chain, chainBox, refsTmp);
                }
            }
            long bitCost = 0;
            for (var i = 1; i >= 0; --i) {
                var cacheBits = i == 1 ? 0 : cacheBitsMax;
                if (i == 1 && !doNoCache) {
                    continue;
                }
                refsTmp = refs[tmpIndex];
                if (i == 0) {
                    cacheBits = calculateBestCacheSize(argb, quality, refsTmp, cacheBits, stats);
                    if (cacheBits > 0) {
                        withLocalCache(argb, cacheBits, refsTmp);
                    }
                }
                if (!(i == 0 && doNoCache && cacheBits == 0)) {
                    histo = new Histogram(cacheBits);
                    histo.create(refsTmp);
                    bitCost = histo.estimateBits(stats);
                }
                if (Long.compareUnsigned(bitCost, bitCostsBest[i]) < 0) {
                    if (i == 1) {
                        refs[1].copyFrom(refsTmp);
                    } else {
                        var swap = refs[0];
                        refs[0] = refs[tmpIndex];
                        refs[tmpIndex] = swap;
                    }
                    bitCostsBest[i] = bitCost;
                    lz77TypesBest[i] = lz77Type;
                    if (i == 0) {
                        cacheBitsBest = cacheBits;
                    }
                }
            }
        }
        for (var i = 1; i >= 0; --i) {
            if (i == 1 && !doNoCache) {
                continue;
            }
            if ((lz77TypesBest[i] == LZ77_STANDARD || lz77TypesBest[i] == LZ77_BOX) && quality >= 25) {
                var chainTmp = lz77TypesBest[i] == LZ77_STANDARD ? chain : chainBox;
                var cacheBits = i == 1 ? 0 : cacheBitsBest;
                var refsTmp = refs[tmpIndex];
                TraceBackwards.run(width, height, argb, cacheBits, chainTmp, refs[i], refsTmp);
                histo = new Histogram(cacheBits);
                histo.create(refsTmp);
                var bitCostTrace = histo.estimateBits(stats);
                if (Long.compareUnsigned(bitCostTrace, bitCostsBest[i]) < 0) {
                    var swap = refs[i];
                    refs[i] = refs[tmpIndex];
                    refs[tmpIndex] = swap;
                }
            }
            to2dLocality(width, refs[i]);
            if (i == 1 && lz77TypesBest[0] == lz77TypesBest[1] && cacheBitsBest == 0) {
                refs[0].copyFrom(refs[1]);
                break;
            }
        }
        return cacheBitsBest;
    }
}
