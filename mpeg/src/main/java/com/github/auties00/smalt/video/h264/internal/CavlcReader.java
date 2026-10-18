package com.github.auties00.smalt.video.h264.internal;

import com.github.auties00.smalt.MediaException;

import java.util.HashMap;
import java.util.TreeSet;

/**
 * Decodes CAVLC residual blocks (ITU-T H.264 9.2) with two-level lookup tables built once from
 * {@link CavlcTables}.
 *
 * <p>Each table maps the next {@code rootBits} bits of the stream to either a final entry
 * {@code (symbol << 8) | length}, a sub-table reference (negative) for longer codes, or 0 for a
 * bit pattern that starts no valid code.
 */
final class CavlcReader {
    /**
     * The root bits of the {@code coeff_token} tables.
     */
    private static final int TOKEN_ROOT = 8;

    /**
     * The root bits of the {@code total_zeros} and {@code run_before} tables.
     */
    private static final int SMALL_ROOT = 9;

    /**
     * The {@code coeff_token} tables for {@code nC} in 0..1, 2..3 and 4..7; symbols are
     * {@code (totalCoeff << 2) | trailingOnes}.
     */
    private static final int[][] TOKEN = new int[3][];

    /**
     * The {@code coeff_token} table for 4:2:0 chroma DC.
     */
    private static final int[] TOKEN_CHROMA_DC;

    /**
     * The {@code total_zeros} tables for 4x4 blocks, indexed by {@code totalCoeff - 1}.
     */
    private static final int[][] TOTAL_ZEROS = new int[15][];

    /**
     * The {@code total_zeros} tables for 4:2:0 chroma DC, indexed by {@code totalCoeff - 1}.
     */
    private static final int[][] TOTAL_ZEROS_CHROMA_DC = new int[3][];

    /**
     * The {@code run_before} tables, indexed by {@code min(zerosLeft, 7) - 1}.
     */
    private static final int[][] RUN_BEFORE = new int[7][];

    /**
     * The short level codes: entry {@code (suffixLength << 8) | next8Bits} is
     * {@code (levelCode << 8) | length} when the next eight bits start a level code of at most
     * eight bits ({@code level_prefix}, the terminating one and a {@code suffixLength}-bit
     * suffix) with {@code suffixLength} from 0 to 6, and 0 otherwise. The context-dependent
     * adjustment of the first non-trailing level is not included.
     */
    private static final int[] LEVEL = new int[7 << 8];

    /**
     * Selects the {@code coeff_token} table index for {@code nC} from 0 to 7.
     */
    private static final byte[] TOKEN_TABLE_OF_NC = {0, 0, 1, 1, 2, 2, 2, 2};

    static {
        for (var suffixLength = 0; suffixLength < 7; suffixLength++) {
            for (var bits = 1; bits < 256; bits++) {
                var prefix = Integer.numberOfLeadingZeros(bits) - 24;
                var length = prefix + 1 + suffixLength;
                if (length > 8) {
                    continue;
                }
                var suffix = (bits >> (8 - length)) & ((1 << suffixLength) - 1);
                LEVEL[(suffixLength << 8) | bits] = (((prefix << suffixLength) + suffix) << 8) | length;
            }
        }
        for (var t = 0; t < 3; t++) {
            var symbols = new int[4 * 17];
            for (var ones = 0; ones < 4; ones++) {
                for (var total = 0; total < 17; total++) {
                    symbols[ones * 17 + total] = (total << 2) | ones;
                }
            }
            TOKEN[t] = build(TOKEN_ROOT, CavlcTables.COEFF_TOKEN_LENGTH[t], CavlcTables.COEFF_TOKEN_CODE[t], symbols);
        }
        var chromaSymbols = new int[4 * 5];
        for (var ones = 0; ones < 4; ones++) {
            for (var total = 0; total < 5; total++) {
                chromaSymbols[ones * 5 + total] = (total << 2) | ones;
            }
        }
        TOKEN_CHROMA_DC = build(TOKEN_ROOT, CavlcTables.CHROMA_DC_TOKEN_LENGTH, CavlcTables.CHROMA_DC_TOKEN_CODE, chromaSymbols);
        for (var i = 0; i < 15; i++) {
            TOTAL_ZEROS[i] = build(SMALL_ROOT, CavlcTables.TOTAL_ZEROS_LENGTH[i], CavlcTables.TOTAL_ZEROS_CODE[i], null);
        }
        for (var i = 0; i < 3; i++) {
            TOTAL_ZEROS_CHROMA_DC[i] = build(SMALL_ROOT, CavlcTables.CHROMA_DC_TOTAL_ZEROS_LENGTH[i],
                    CavlcTables.CHROMA_DC_TOTAL_ZEROS_CODE[i], null);
        }
        for (var i = 0; i < 7; i++) {
            RUN_BEFORE[i] = build(SMALL_ROOT, CavlcTables.RUN_BEFORE_LENGTH[i], CavlcTables.RUN_BEFORE_CODE[i], null);
        }
    }

    /**
     * The decoded levels of the current block, in reverse scan order.
     */
    private final int[] levels = new int[16];

    /**
     * Constructs a reader.
     */
    CavlcReader() {
    }

    /**
     * Builds a two-level lookup table from code lengths and values.
     *
     * @param rootBits the number of bits indexing the root table
     * @param lengths the code length of each symbol, 0 for no code
     * @param codes the code value of each symbol
     * @param symbols the symbol of each entry, or {@code null} to use the entry index
     * @return the table
     */
    private static int[] build(int rootBits, byte[] lengths, byte[] codes, int[] symbols) {
        var maxLength = 0;
        for (var length : lengths) {
            maxLength = Math.max(maxLength, length);
        }
        var rootSize = 1 << rootBits;
        var subBits = Math.max(0, maxLength - rootBits);
        var subSize = 1 << subBits;
        var prefixes = new TreeSet<Integer>();
        for (var i = 0; i < lengths.length; i++) {
            if (lengths[i] > rootBits) {
                prefixes.add((codes[i] & 0xFF) >>> (lengths[i] - rootBits));
            }
        }
        var table = new int[rootSize + prefixes.size() * subSize];
        var subOffsets = new HashMap<Integer, Integer>();
        var next = rootSize;
        for (var prefix : prefixes) {
            subOffsets.put(prefix, next);
            table[prefix] = 0x80000000 | (next << 8) | subBits;
            next += subSize;
        }
        for (var i = 0; i < lengths.length; i++) {
            var length = lengths[i];
            if (length == 0) {
                continue;
            }
            var code = codes[i] & 0xFF;
            var symbol = symbols != null ? symbols[i] : i;
            if (length <= rootBits) {
                var shift = rootBits - length;
                var first = code << shift;
                for (var j = 0; j < (1 << shift); j++) {
                    table[first + j] = (symbol << 8) | length;
                }
            } else {
                var prefix = code >>> (length - rootBits);
                var base = subOffsets.get(prefix);
                var remaining = length - rootBits;
                var shift = subBits - remaining;
                var first = (code & ((1 << remaining) - 1)) << shift;
                for (var j = 0; j < (1 << shift); j++) {
                    table[base + first + j] = (symbol << 8) | remaining;
                }
            }
        }
        return table;
    }

    /**
     * Decodes one residual block ({@code residual_block_cavlc()}, ITU-T H.264 7.3.5.3.2) and
     * stores its dequantized coefficients.
     *
     * @implNote This implementation works on local copies of the reader's bit cache and refills
     * it only where a following code could run out of bits (before the coefficient token, before
     * each level, before {@code total_zeros} and before each {@code run_before}), so the table
     * lookups read the cache directly without per-read bounds checks; the reader state is written
     * back once. Level codes of at most eight bits, the common case, are decoded with one lookup
     * in {@link #LEVEL}.
     * @param reader the bit reader
     * @param nC the {@code nC} context: 0 and above for luma and chroma AC blocks,
     * {@code -1} for 4:2:0 chroma DC
     * @param maxCoeff the {@code maxNumCoeff}: 4, 15 or 16
     * @param coeffs receives the coefficients
     * @param coeffOffset the index of the block in {@code coeffs}
     * @param scan maps coefficient index {@code startIdx + k} to its position within the
     * block (added to {@code coeffOffset})
     * @param scanStart the scan index of the first coded coefficient: 0, or 1 for AC blocks
     * @param scanStep the step between successive scan positions in {@code scan}: 1, or 4 for
     * the interleaved 4x4 parts of an 8x8 block
     * @param scanBase the scan index offset: 0, or the 4x4 part index for interleaved 8x8
     * blocks
     * @param dequant the scale factor of each block position, or {@code null} to store raw
     * levels at their scan index (DC blocks) instead of their block position
     * @param qpShift the left shift ({@code >= 0}) or rounding right shift ({@code < 0})
     * applied after scaling
     * @return the {@code TotalCoeff} of the block
     * @throws MediaException.Malformed if the block is not a valid CAVLC block
     */
    int residualBlock(H264BitReader reader, int nC, int maxCoeff, int[] coeffs, int coeffOffset,
                      byte[] scan, int scanStart, int scanStep, int scanBase, int[] dequant, int qpShift) {
        var data = reader.data;
        var loadLimit = reader.loadLimit;
        var next = reader.next;
        var cache = reader.cache;
        var count = reader.count;
        if (count < 32) {
            var bytes = (63 - count) >>> 3;
            if (next <= loadLimit) {
                cache |= ((long) H264BitReader.LONG_BE.get(data, next)) >>> count;
            }
            next += bytes;
            count += bytes << 3;
        }
        int symbol;
        if (nC >= 8) {
            var code = (int) (cache >>> 58);
            cache <<= 6;
            count -= 6;
            symbol = code == 3 ? 0 : (((code >> 2) + 1) << 2) | (code & 3);
        } else {
            var table = nC < 0 ? TOKEN_CHROMA_DC : TOKEN[TOKEN_TABLE_OF_NC[nC]];
            var entry = table[(int) (cache >>> (64 - TOKEN_ROOT))];
            if (entry <= 0) {
                if (entry == 0) {
                    throw new MediaException.Malformed("invalid CAVLC code");
                }
                cache <<= TOKEN_ROOT;
                count -= TOKEN_ROOT;
                entry = table[((entry >>> 8) & 0x7FFFFF) + (int) (cache >>> (64 - (entry & 0xFF)))];
                if (entry == 0) {
                    throw new MediaException.Malformed("invalid CAVLC code");
                }
            }
            cache <<= entry & 0xFF;
            count -= entry & 0xFF;
            symbol = entry >>> 8;
        }
        var totalCoeff = symbol >> 2;
        var trailingOnes = symbol & 3;
        if (totalCoeff == 0) {
            reader.cache = cache;
            reader.count = count;
            reader.next = next;
            return 0;
        }
        if (totalCoeff > maxCoeff || trailingOnes > totalCoeff) {
            throw new MediaException.Malformed("invalid coeff_token");
        }
        var levels = this.levels;
        if (trailingOnes > 0) {
            var signs = (int) (cache >>> (64 - trailingOnes));
            cache <<= trailingOnes;
            count -= trailingOnes;
            for (var i = 0; i < trailingOnes; i++) {
                levels[i] = 1 - 2 * ((signs >> (trailingOnes - 1 - i)) & 1);
            }
        }
        var suffixLength = totalCoeff > 10 && trailingOnes < 3 ? 1 : 0;
        for (var i = trailingOnes; i < totalCoeff; i++) {
            if (count < 48) {
                var bytes = (63 - count) >>> 3;
                if (next <= loadLimit) {
                    cache |= ((long) H264BitReader.LONG_BE.get(data, next)) >>> count;
                }
                next += bytes;
                count += bytes << 3;
            }
            var entry = LEVEL[(suffixLength << 8) | (int) (cache >>> 56)];
            int levelCode;
            if (entry != 0) {
                var length = entry & 0xFF;
                cache <<= length;
                count -= length;
                levelCode = entry >>> 8;
            } else {
                var prefix = Math.min(Long.numberOfLeadingZeros(cache), 32);
                if (prefix > 25) {
                    throw new MediaException.Malformed("invalid level_prefix");
                }
                cache <<= prefix + 1;
                count -= prefix + 1;
                levelCode = Math.min(15, prefix) << suffixLength;
                if (suffixLength > 0 || prefix >= 14) {
                    int suffixSize;
                    if (prefix == 14 && suffixLength == 0) {
                        suffixSize = 4;
                    } else if (prefix >= 15) {
                        suffixSize = prefix - 3;
                    } else {
                        suffixSize = suffixLength;
                    }
                    if (suffixSize > 0) {
                        levelCode += (int) (cache >>> (64 - suffixSize));
                        cache <<= suffixSize;
                        count -= suffixSize;
                    }
                }
                if (prefix >= 15 && suffixLength == 0) {
                    levelCode += 15;
                }
                if (prefix >= 16) {
                    levelCode += (1 << (prefix - 3)) - 4096;
                }
            }
            if (i == trailingOnes && trailingOnes < 3) {
                levelCode += 2;
            }
            int level;
            if ((levelCode & 1) == 0) {
                level = (levelCode + 2) >> 1;
            } else {
                level = (-levelCode - 1) >> 1;
            }
            levels[i] = level;
            if (suffixLength == 0) {
                suffixLength = 1;
            }
            if (Math.abs(level) > (3 << (suffixLength - 1)) && suffixLength < 6) {
                suffixLength++;
            }
        }
        if (count < 32) {
            var bytes = (63 - count) >>> 3;
            if (next <= loadLimit) {
                cache |= ((long) H264BitReader.LONG_BE.get(data, next)) >>> count;
            }
            next += bytes;
            count += bytes << 3;
        }
        int zerosLeft;
        if (totalCoeff < maxCoeff) {
            var table = maxCoeff == 4 ? TOTAL_ZEROS_CHROMA_DC[totalCoeff - 1] : TOTAL_ZEROS[totalCoeff - 1];
            var entry = table[(int) (cache >>> (64 - SMALL_ROOT))];
            if (entry <= 0) {
                if (entry == 0) {
                    throw new MediaException.Malformed("invalid CAVLC code");
                }
                cache <<= SMALL_ROOT;
                count -= SMALL_ROOT;
                entry = table[((entry >>> 8) & 0x7FFFFF) + (int) (cache >>> (64 - (entry & 0xFF)))];
                if (entry == 0) {
                    throw new MediaException.Malformed("invalid CAVLC code");
                }
            }
            cache <<= entry & 0xFF;
            count -= entry & 0xFF;
            zerosLeft = entry >>> 8;
            if (zerosLeft + totalCoeff > maxCoeff) {
                throw new MediaException.Malformed("invalid total_zeros");
            }
        } else {
            zerosLeft = 0;
        }
        var position = totalCoeff - 1 + zerosLeft;
        for (var i = 0; i < totalCoeff; i++) {
            var index = scanBase + (scanStart + position) * scanStep;
            var level = levels[i];
            if (dequant != null) {
                var raster = scan[index];
                var scaled = level * dequant[raster];
                coeffs[coeffOffset + raster] = qpShift >= 0 ? scaled << qpShift : (scaled + (1 << (-qpShift - 1))) >> -qpShift;
            } else {
                coeffs[coeffOffset + index] = level;
            }
            if (i < totalCoeff - 1) {
                if (zerosLeft > 0) {
                    if (count < 16) {
                        var bytes = (63 - count) >>> 3;
                        if (next <= loadLimit) {
                            cache |= ((long) H264BitReader.LONG_BE.get(data, next)) >>> count;
                        }
                        next += bytes;
                        count += bytes << 3;
                    }
                    var table = RUN_BEFORE[Math.min(zerosLeft, 7) - 1];
                    var entry = table[(int) (cache >>> (64 - SMALL_ROOT))];
                    if (entry <= 0) {
                        if (entry == 0) {
                            throw new MediaException.Malformed("invalid CAVLC code");
                        }
                        cache <<= SMALL_ROOT;
                        count -= SMALL_ROOT;
                        entry = table[((entry >>> 8) & 0x7FFFFF) + (int) (cache >>> (64 - (entry & 0xFF)))];
                        if (entry == 0) {
                            throw new MediaException.Malformed("invalid CAVLC code");
                        }
                    }
                    cache <<= entry & 0xFF;
                    count -= entry & 0xFF;
                    var run = entry >>> 8;
                    if (run > zerosLeft) {
                        throw new MediaException.Malformed("invalid run_before");
                    }
                    zerosLeft -= run;
                    position -= run + 1;
                } else {
                    position--;
                }
            }
        }
        reader.cache = cache;
        reader.count = count;
        reader.next = next;
        return totalCoeff;
    }
}
