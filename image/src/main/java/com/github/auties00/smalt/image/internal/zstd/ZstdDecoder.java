package com.github.auties00.smalt.image.internal.zstd;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.DataFormatException;

/**
 * Decompresses Zstandard data (RFC 8878) held in memory into a caller buffer of known size.
 *
 * <p>The input is a sequence of frames: Zstandard frames, whose content is appended to the output,
 * and skippable frames, which are ignored. Decoding stops when the output is full, when the input
 * ends, or at the first error; the bytes produced before that point are in the output, and
 * {@link #produced()} counts them. Frames that need a dictionary are rejected, and a back-reference
 * may not reach before the start of its frame. When a frame is decoded in full, its content size and
 * content checksum are checked.
 *
 * <p>Instances are not thread-safe; an instance keeps its tables and literal buffer between calls.
 */
public final class ZstdDecoder {
    /** The magic number of a Zstandard frame. */
    static final int MAGIC = 0xFD2FB528;

    /** The magic number of a skippable frame, ignoring its low four bits. */
    static final int SKIPPABLE_MAGIC = 0x184D2A50;

    /** The largest block size. */
    static final int MAX_BLOCK = 128 * 1024;

    /** The largest accuracy log of the literal length and match length tables. */
    private static final int MAX_LENGTH_LOG = 9;

    /** The largest accuracy log of the offset table. */
    private static final int MAX_OFFSET_LOG = 8;

    /** The largest accuracy log of the Huffman weight table. */
    private static final int MAX_WEIGHT_LOG = 6;

    /** The longest Huffman code. */
    static final int MAX_HUFFMAN_BITS = 11;

    /** The largest offset code this decoder accepts. */
    private static final int MAX_OFFSET_CODE = 31;

    /** The little-endian 64-bit view of byte arrays. */
    private static final VarHandle LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /** The literal length baselines, by code. */
    static final int[] LL_BASE = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
            16, 18, 20, 22, 24, 28, 32, 40, 48, 64, 128, 256, 512, 1024, 2048, 4096,
            8192, 16384, 32768, 65536};

    /** The literal length extra bits, by code. */
    static final int[] LL_BITS = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            1, 1, 1, 1, 2, 2, 3, 3, 4, 6, 7, 8, 9, 10, 11, 12,
            13, 14, 15, 16};

    /** The match length baselines, by code. */
    static final int[] ML_BASE = {
            3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18,
            19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34,
            35, 37, 39, 41, 43, 47, 51, 59, 67, 83, 99, 131, 259, 515, 1027, 2051,
            4099, 8195, 16387, 32771, 65539};

    /** The match length extra bits, by code. */
    static final int[] ML_BITS = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            1, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 7, 8, 9, 10, 11,
            12, 13, 14, 15, 16};

    /** The predefined literal length distribution, accuracy log 6. */
    static final short[] LL_DEFAULT = {
            4, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1,
            2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 1, 1, 1, 1, 1,
            -1, -1, -1, -1};

    /** The predefined match length distribution, accuracy log 6. */
    static final short[] ML_DEFAULT = {
            1, 4, 3, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1,
            -1, -1, -1, -1, -1};

    /** The predefined offset code distribution, accuracy log 5. */
    static final short[] OF_DEFAULT = {
            1, 1, 1, 1, 1, 1, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1};

    /** The literal length table and its predefined copy. */
    private final FseTable literalLengths = new FseTable(MAX_LENGTH_LOG);
    private final FseTable defaultLiteralLengths = new FseTable(6);

    /** The match length table and its predefined copy. */
    private final FseTable matchLengths = new FseTable(MAX_LENGTH_LOG);
    private final FseTable defaultMatchLengths = new FseTable(6);

    /** The offset table and its predefined copy. */
    private final FseTable offsets = new FseTable(MAX_OFFSET_LOG);
    private final FseTable defaultOffsets = new FseTable(5);

    /** The table decoding the FSE-compressed Huffman weights. */
    private final FseTable weightTable = new FseTable(MAX_WEIGHT_LOG);

    /** The tables the sequences use: the decoder's own, or the predefined ones. */
    private FseTable llTable;
    private FseTable mlTable;
    private FseTable ofTable;

    /** The Huffman decoding table: symbol and code length for each 11-bit prefix. */
    private final byte[] huffmanSymbols = new byte[1 << MAX_HUFFMAN_BITS];
    private final byte[] huffmanLengths = new byte[1 << MAX_HUFFMAN_BITS];

    /** The code length of the Huffman table, or 0 when the frame has none yet. */
    private int huffmanBits;

    /** The decoded Huffman weights of one tree description. */
    private final byte[] weights = new byte[256];

    /** The normalized counts read from a table description. */
    private final short[] counts = new short[256];

    /** The decoded literals of a block. */
    private final byte[] literals = new byte[MAX_BLOCK + 8];

    /** The source of the literals of the current block: {@link #literals} or the input. */
    private byte[] literalSource;
    private int literalStart;
    private int literalCount;

    /** The backward bit reader. */
    private final BackwardBits bits = new BackwardBits();

    /** The three most recent offsets. */
    private long rep1;
    private long rep2;
    private long rep3;

    /** Whether the frame has decoded a sequence table to repeat. */
    private boolean sequenceTables;

    /** The output of the current call. */
    private byte[] out;
    private int outPos;
    private int outEnd;

    /** The first output position of the current frame. */
    private int frameStart;

    /** The bytes produced by the last call. */
    private int produced;

    /** Whether the output filled up before the current frame ended. */
    private boolean clipped;

    /** Whether the last call failed because the input ended. */
    private boolean truncated;

    /**
     * Constructs a decoder.
     */
    public ZstdDecoder() {
        defaultLiteralLengths.build(LL_DEFAULT, LL_DEFAULT.length - 1, 6);
        defaultMatchLengths.build(ML_DEFAULT, ML_DEFAULT.length - 1, 6);
        defaultOffsets.build(OF_DEFAULT, OF_DEFAULT.length - 1, 5);
    }

    /**
     * Decompresses frames into an output range.
     *
     * @param src the input
     * @param srcOff the first input byte
     * @param srcLen the number of input bytes
     * @param dst the output
     * @param dstOff the first output byte
     * @param dstLen the room in the output, at most this many bytes are written
     * @return the number of bytes produced
     * @throws DataFormatException if the data is not valid Zstandard data; {@link #produced()} then
     * counts the bytes written before the error
     */
    public int decompress(byte[] src, int srcOff, int srcLen, byte[] dst, int dstOff, int dstLen)
            throws DataFormatException {
        out = dst;
        outPos = dstOff;
        outEnd = dstOff + dstLen;
        produced = 0;
        truncated = false;
        try {
            var pos = srcOff;
            var end = srcOff + srcLen;
            while (pos < end && outPos < outEnd) {
                if (end - pos < 4) {
                    throw truncated("truncated Zstandard frame");
                }
                var magic = intLE(src, pos);
                if ((magic & 0xFFFFFFF0) == SKIPPABLE_MAGIC) {
                    if (end - pos < 8) {
                        throw truncated("truncated skippable frame");
                    }
                    var size = intLE(src, pos + 4) & 0xFFFFFFFFL;
                    if (size > end - pos - 8) {
                        throw truncated("truncated skippable frame");
                    }
                    pos += 8 + (int) size;
                    continue;
                }
                if (magic != MAGIC) {
                    throw new DataFormatException("not a Zstandard frame");
                }
                pos = frame(src, pos + 4, end);
            }
            return outPos - dstOff;
        } finally {
            produced = outPos - dstOff;
            out = null;
        }
    }

    /**
     * {@return the number of bytes the last call of {@link #decompress} produced, including one
     * that failed}
     */
    public int produced() {
        return produced;
    }

    /**
     * {@return whether the last call of {@link #decompress} failed because the input ended before the
     * data it describes}
     */
    public boolean truncated() {
        return truncated;
    }

    /**
     * Records that the input ended early.
     *
     * @param message the detail message of the exception
     * @return the exception to throw
     */
    private DataFormatException truncated(String message) {
        truncated = true;
        return new DataFormatException(message);
    }

    /**
     * Decodes one Zstandard frame after its magic number.
     *
     * @param src the input
     * @param pos the frame header position
     * @param end the end of the input
     * @return the position after the frame, or any position when the output filled up
     * @throws DataFormatException if the frame is invalid
     */
    private int frame(byte[] src, int pos, int end) throws DataFormatException {
        if (pos >= end) {
            throw truncated("truncated Zstandard frame header");
        }
        var descriptor = src[pos++] & 0xFF;
        var fcsFlag = descriptor >>> 6;
        var singleSegment = (descriptor & 0x20) != 0;
        var checksum = (descriptor & 0x04) != 0;
        var dictionaryFlag = descriptor & 3;
        if ((descriptor & 0x08) != 0) {
            throw new DataFormatException("reserved Zstandard frame header bit set");
        }
        var headerBytes = (singleSegment ? 0 : 1) + (dictionaryFlag == 3 ? 4 : dictionaryFlag)
                + (fcsFlag == 0 ? (singleSegment ? 1 : 0) : 1 << fcsFlag);
        if (end - pos < headerBytes) {
            throw truncated("truncated Zstandard frame header");
        }
        var window = -1L;
        if (!singleSegment) {
            var windowDescriptor = src[pos++] & 0xFF;
            var windowLog = 10 + (windowDescriptor >>> 3);
            var windowBase = 1L << windowLog;
            window = windowBase + (windowBase >>> 3) * (windowDescriptor & 7);
        }
        var dictionaryId = 0L;
        for (var i = 0; i < (dictionaryFlag == 3 ? 4 : dictionaryFlag); i++) {
            dictionaryId |= (long) (src[pos++] & 0xFF) << (8 * i);
        }
        if (dictionaryId != 0) {
            throw new DataFormatException("the Zstandard frame needs dictionary " + dictionaryId);
        }
        var contentSize = -1L;
        var fcsBytes = fcsFlag == 0 ? (singleSegment ? 1 : 0) : 1 << fcsFlag;
        if (fcsBytes > 0) {
            contentSize = 0;
            for (var i = 0; i < fcsBytes; i++) {
                contentSize |= (long) (src[pos++] & 0xFF) << (8 * i);
            }
            if (fcsBytes == 2) {
                contentSize += 256;
            }
        }
        if (singleSegment) {
            window = contentSize;
        }
        var maxBlock = (int) Math.min(MAX_BLOCK, Math.max(window, 0));
        frameStart = outPos;
        clipped = false;
        rep1 = 1;
        rep2 = 4;
        rep3 = 8;
        huffmanBits = 0;
        sequenceTables = false;
        var hash = checksum ? new XxHash64() : null;
        while (true) {
            if (end - pos < 3) {
                throw truncated("truncated Zstandard block header");
            }
            var header = (src[pos] & 0xFF) | (src[pos + 1] & 0xFF) << 8 | (src[pos + 2] & 0xFF) << 16;
            pos += 3;
            var last = (header & 1) != 0;
            var type = (header >>> 1) & 3;
            var size = header >>> 3;
            var blockStart = outPos;
            switch (type) {
                case 0 -> {
                    if (size > maxBlock) {
                        throw new DataFormatException("Zstandard block larger than the maximum block size");
                    }
                    if (end - pos < size) {
                        copyRaw(src, pos, end - pos);
                        throw truncated("truncated raw Zstandard block");
                    }
                    copyRaw(src, pos, size);
                    pos += size;
                }
                case 1 -> {
                    if (size > maxBlock) {
                        throw new DataFormatException("Zstandard block larger than the maximum block size");
                    }
                    if (pos >= end) {
                        throw truncated("truncated RLE Zstandard block");
                    }
                    var count = Math.min(size, outEnd - outPos);
                    clipped |= count < size;
                    Arrays.fill(out, outPos, outPos + count, src[pos]);
                    outPos += count;
                    pos++;
                }
                case 2 -> {
                    if (size > maxBlock) {
                        throw new DataFormatException("Zstandard block larger than the maximum block size");
                    }
                    if (end - pos < size) {
                        throw truncated("truncated compressed Zstandard block");
                    }
                    compressedBlock(src, pos, pos + size, maxBlock);
                    pos += size;
                }
                default -> throw new DataFormatException("reserved Zstandard block type");
            }
            if (hash != null) {
                hash.update(out, blockStart, outPos - blockStart);
            }
            if (clipped || (outPos >= outEnd && !last)) {
                return pos;
            }
            if (last) {
                break;
            }
        }
        if (clipped) {
            return pos;
        }
        if (contentSize >= 0 && contentSize != outPos - frameStart) {
            throw new DataFormatException("the Zstandard frame content size does not match its blocks");
        }
        if (checksum) {
            if (end - pos < 4) {
                throw truncated("truncated Zstandard content checksum");
            }
            if ((int) hash.digest() != intLE(src, pos)) {
                throw new DataFormatException("Zstandard content checksum mismatch");
            }
            pos += 4;
        }
        return pos;
    }

    /**
     * Copies raw bytes to the output, as many as fit.
     *
     * @param src the input
     * @param pos the first byte
     * @param count the number of bytes
     */
    private void copyRaw(byte[] src, int pos, int count) {
        var copy = Math.min(count, outEnd - outPos);
        clipped |= copy < count;
        System.arraycopy(src, pos, out, outPos, copy);
        outPos += copy;
    }

    /**
     * Decodes one compressed block.
     *
     * @param src the input
     * @param pos the first byte of the block content
     * @param end the end of the block content
     * @param maxBlock the largest decompressed block size
     * @throws DataFormatException if the block is invalid
     */
    private void compressedBlock(byte[] src, int pos, int end, int maxBlock) throws DataFormatException {
        pos = literalsSection(src, pos, end, maxBlock);
        if (pos >= end) {
            throw new DataFormatException("truncated Zstandard sequences section");
        }
        var byte0 = src[pos++] & 0xFF;
        int sequences;
        if (byte0 < 128) {
            sequences = byte0;
        } else if (byte0 < 255) {
            if (pos >= end) {
                throw new DataFormatException("truncated Zstandard sequences section");
            }
            sequences = ((byte0 - 0x80) << 8) + (src[pos++] & 0xFF);
        } else {
            if (end - pos < 2) {
                throw new DataFormatException("truncated Zstandard sequences section");
            }
            sequences = (src[pos] & 0xFF) + ((src[pos + 1] & 0xFF) << 8) + 0x7F00;
            pos += 2;
        }
        var blockStart = outPos;
        if (sequences == 0) {
            if (pos != end) {
                throw new DataFormatException("extra bytes after a Zstandard block without sequences");
            }
            copyLiterals(literalStart, literalCount);
            return;
        }
        if (pos >= end) {
            throw new DataFormatException("truncated Zstandard sequences section");
        }
        var modes = src[pos++] & 0xFF;
        if ((modes & 3) != 0) {
            throw new DataFormatException("reserved Zstandard sequence mode bits set");
        }
        pos = table(src, pos, end, (modes >>> 6) & 3, 0);
        pos = table(src, pos, end, (modes >>> 4) & 3, 1);
        pos = table(src, pos, end, (modes >>> 2) & 3, 2);
        sequenceTables = true;
        executeSequences(src, pos, end, sequences, maxBlock, blockStart);
    }

    /**
     * Reads the literals section of a compressed block.
     *
     * @param src the input
     * @param pos the section position
     * @param end the end of the block
     * @param maxBlock the largest decompressed block size
     * @return the position after the section
     * @throws DataFormatException if the section is invalid
     */
    private int literalsSection(byte[] src, int pos, int end, int maxBlock) throws DataFormatException {
        if (pos >= end) {
            throw new DataFormatException("truncated Zstandard literals section");
        }
        var b0 = src[pos] & 0xFF;
        var type = b0 & 3;
        var sizeFormat = (b0 >>> 2) & 3;
        if (type < 2) {
            int regenerated;
            switch (sizeFormat) {
                case 0, 2 -> {
                    regenerated = b0 >>> 3;
                    pos += 1;
                }
                case 1 -> {
                    if (end - pos < 2) {
                        throw new DataFormatException("truncated Zstandard literals header");
                    }
                    regenerated = (b0 >>> 4) + ((src[pos + 1] & 0xFF) << 4);
                    pos += 2;
                }
                default -> {
                    if (end - pos < 3) {
                        throw new DataFormatException("truncated Zstandard literals header");
                    }
                    regenerated = (b0 >>> 4) + ((src[pos + 1] & 0xFF) << 4) + ((src[pos + 2] & 0xFF) << 12);
                    pos += 3;
                }
            }
            if (regenerated > maxBlock) {
                throw new DataFormatException("Zstandard literals larger than the maximum block size");
            }
            if (type == 0) {
                if (end - pos < regenerated) {
                    throw new DataFormatException("truncated raw Zstandard literals");
                }
                literalSource = src;
                literalStart = pos;
                literalCount = regenerated;
                return pos + regenerated;
            }
            if (pos >= end) {
                throw new DataFormatException("truncated RLE Zstandard literals");
            }
            Arrays.fill(literals, 0, regenerated, src[pos]);
            literalSource = literals;
            literalStart = 0;
            literalCount = regenerated;
            return pos + 1;
        }
        int regenerated;
        int compressed;
        var streams = sizeFormat == 0 ? 1 : 4;
        switch (sizeFormat) {
            case 0, 1 -> {
                if (end - pos < 3) {
                    throw new DataFormatException("truncated Zstandard literals header");
                }
                var value = b0 | (src[pos + 1] & 0xFF) << 8 | (src[pos + 2] & 0xFF) << 16;
                regenerated = (value >>> 4) & 0x3FF;
                compressed = (value >>> 14) & 0x3FF;
                pos += 3;
            }
            case 2 -> {
                if (end - pos < 4) {
                    throw new DataFormatException("truncated Zstandard literals header");
                }
                var value = intLE(src, pos);
                regenerated = (value >>> 4) & 0x3FFF;
                compressed = (value >>> 18) & 0x3FFF;
                pos += 4;
            }
            default -> {
                if (end - pos < 5) {
                    throw new DataFormatException("truncated Zstandard literals header");
                }
                var value = (intLE(src, pos) & 0xFFFFFFFFL) | (long) (src[pos + 4] & 0xFF) << 32;
                regenerated = (int) ((value >>> 4) & 0x3FFFF);
                compressed = (int) ((value >>> 22) & 0x3FFFF);
                pos += 5;
            }
        }
        if (regenerated > maxBlock) {
            throw new DataFormatException("Zstandard literals larger than the maximum block size");
        }
        if (compressed > end - pos || compressed == 0) {
            throw new DataFormatException("invalid compressed Zstandard literals size");
        }
        var streamsEnd = pos + compressed;
        if (type == 2) {
            pos = huffmanTree(src, pos, streamsEnd);
        } else if (huffmanBits == 0) {
            throw new DataFormatException("Zstandard treeless literals without a previous Huffman table");
        }
        if (streams == 1) {
            huffmanStream(src, pos, streamsEnd, 0, regenerated);
        } else {
            if (streamsEnd - pos < 10) {
                throw new DataFormatException("truncated Zstandard literal streams");
            }
            var size1 = (src[pos] & 0xFF) | (src[pos + 1] & 0xFF) << 8;
            var size2 = (src[pos + 2] & 0xFF) | (src[pos + 3] & 0xFF) << 8;
            var size3 = (src[pos + 4] & 0xFF) | (src[pos + 5] & 0xFF) << 8;
            var start1 = pos + 6;
            var start2 = start1 + size1;
            var start3 = start2 + size2;
            var start4 = start3 + size3;
            if (start4 >= streamsEnd) {
                throw new DataFormatException("invalid Zstandard literal stream sizes");
            }
            var segment = (regenerated + 3) / 4;
            if (3 * segment > regenerated) {
                throw new DataFormatException("too few Zstandard literals for four streams");
            }
            huffmanStream(src, start1, start2, 0, segment);
            huffmanStream(src, start2, start3, segment, segment);
            huffmanStream(src, start3, start4, 2 * segment, segment);
            huffmanStream(src, start4, streamsEnd, 3 * segment, regenerated - 3 * segment);
        }
        literalSource = literals;
        literalStart = 0;
        literalCount = regenerated;
        return streamsEnd;
    }

    /**
     * Reads a Huffman tree description and builds the decoding table.
     *
     * @param src the input
     * @param pos the description position
     * @param end the end of the literal section
     * @return the position after the description
     * @throws DataFormatException if the description is invalid
     */
    private int huffmanTree(byte[] src, int pos, int end) throws DataFormatException {
        if (pos >= end) {
            throw new DataFormatException("truncated Zstandard Huffman tree");
        }
        var header = src[pos++] & 0xFF;
        int count;
        if (header >= 128) {
            count = header - 127;
            var bytes = (count + 1) / 2;
            if (end - pos < bytes) {
                throw new DataFormatException("truncated Zstandard Huffman weights");
            }
            for (var i = 0; i < count; i++) {
                var value = src[pos + i / 2] & 0xFF;
                weights[i] = (byte) ((i & 1) == 0 ? value >>> 4 : value & 15);
            }
            pos += bytes;
        } else {
            if (end - pos < header || header == 0) {
                throw new DataFormatException("truncated Zstandard Huffman weights");
            }
            count = fseWeights(src, pos, pos + header);
            pos += header;
        }
        var total = 0;
        for (var i = 0; i < count; i++) {
            var weight = weights[i];
            if (weight > MAX_HUFFMAN_BITS) {
                throw new DataFormatException("invalid Zstandard Huffman weight");
            }
            if (weight > 0) {
                total += 1 << (weight - 1);
            }
        }
        if (total == 0) {
            throw new DataFormatException("Zstandard Huffman tree without symbols");
        }
        var maxBits = 32 - Integer.numberOfLeadingZeros(total);
        if (maxBits > MAX_HUFFMAN_BITS) {
            throw new DataFormatException("Zstandard Huffman tree deeper than 11 bits");
        }
        var rest = (1 << maxBits) - total;
        if (Integer.bitCount(rest) != 1) {
            throw new DataFormatException("Zstandard Huffman weights do not complete a tree");
        }
        if (count >= 256) {
            throw new DataFormatException("too many Zstandard Huffman weights");
        }
        weights[count] = (byte) (Integer.numberOfTrailingZeros(rest) + 1);
        count++;
        var ones = 0;
        var present = 0;
        for (var i = 0; i < count; i++) {
            if (weights[i] == 1) {
                ones++;
            }
            if (weights[i] > 0) {
                present++;
            }
        }
        if (ones == 0 || present < 2) {
            throw new DataFormatException("invalid Zstandard Huffman weights");
        }
        var at = 0;
        for (var weight = 1; weight <= maxBits; weight++) {
            var span = 1 << (weight - 1);
            var length = (byte) (maxBits + 1 - weight);
            for (var symbol = 0; symbol < count; symbol++) {
                if (weights[symbol] == weight) {
                    Arrays.fill(huffmanSymbols, at, at + span, (byte) symbol);
                    Arrays.fill(huffmanLengths, at, at + span, length);
                    at += span;
                }
            }
        }
        huffmanBits = maxBits;
        return pos;
    }

    /**
     * Decodes FSE-compressed Huffman weights into {@link #weights}.
     *
     * @param src the input
     * @param pos the first byte of the compressed weights
     * @param end the end of the compressed weights
     * @return the number of weights
     * @throws DataFormatException if the weights are invalid
     */
    private int fseWeights(byte[] src, int pos, int end) throws DataFormatException {
        var start = readCounts(src, pos, end, MAX_WEIGHT_LOG, MAX_HUFFMAN_BITS + 1);
        weightTable.build(counts, lastSymbol, accuracy);
        var reader = bits;
        reader.init(src, start, end);
        var log = weightTable.log;
        var state1 = (int) reader.read(log);
        reader.reload();
        var state2 = (int) reader.read(log);
        reader.reload();
        if (reader.overflowed()) {
            throw new DataFormatException("truncated Zstandard Huffman weights");
        }
        var count = 0;
        while (true) {
            count = weight(count, state1);
            state1 = weightTable.baselines[state1] + (int) reader.read(weightTable.bits[state1]);
            reader.reload();
            if (reader.overflowed()) {
                return weight(count, state2);
            }
            count = weight(count, state2);
            state2 = weightTable.baselines[state2] + (int) reader.read(weightTable.bits[state2]);
            reader.reload();
            if (reader.overflowed()) {
                return weight(count, state1);
            }
        }
    }

    /**
     * Appends the weight of a state of the weight table.
     *
     * @param count the number of weights so far
     * @param state the decoder state whose symbol is the weight
     * @return the new number of weights
     * @throws DataFormatException if there would be more than 255 weights
     */
    private int weight(int count, int state) throws DataFormatException {
        if (count >= 255) {
            throw new DataFormatException("too many Zstandard Huffman weights");
        }
        weights[count] = (byte) weightTable.symbols[state];
        return count + 1;
    }

    /**
     * Decodes one Huffman-coded literal stream.
     *
     * @param src the input
     * @param start the first byte of the stream
     * @param end the end of the stream
     * @param at the first literal to write
     * @param count the number of literals in the stream
     * @throws DataFormatException if the stream is invalid
     */
    private void huffmanStream(byte[] src, int start, int end, int at, int count) throws DataFormatException {
        var reader = bits;
        reader.init(src, start, end);
        var maxBits = huffmanBits;
        var shift = 64 - maxBits;
        var symbols = huffmanSymbols;
        var lengths = huffmanLengths;
        var limit = at + count;
        var i = at;
        while (i + 4 <= limit) {
            reader.reload();
            for (var k = 0; k < 4; k++) {
                var index = reader.peek(shift);
                literals[i++] = symbols[index];
                reader.skip(lengths[index]);
            }
        }
        while (i < limit) {
            reader.reload();
            var index = reader.peek(shift);
            literals[i++] = symbols[index];
            reader.skip(lengths[index]);
        }
        reader.reload();
        if (!reader.finished()) {
            throw new DataFormatException("invalid Zstandard Huffman literal stream");
        }
    }

    /** The accuracy log read by {@link #readCounts}. */
    private int accuracy;

    /** The last symbol read by {@link #readCounts}. */
    private int lastSymbol;

    /**
     * Reads an FSE table description into {@link #counts}, {@link #accuracy} and {@link #lastSymbol}.
     *
     * @param src the input
     * @param pos the description position
     * @param end the end of the available input
     * @param maxLog the largest accuracy log allowed
     * @param maxSymbol the largest symbol allowed
     * @return the position after the description
     * @throws DataFormatException if the description is invalid
     */
    private int readCounts(byte[] src, int pos, int end, int maxLog, int maxSymbol) throws DataFormatException {
        var bitPos = (long) pos * 8;
        var bitEnd = (long) end * 8;
        if (bitEnd - bitPos < 4) {
            throw new DataFormatException("truncated Zstandard FSE table");
        }
        var log = (int) readForward(src, bitPos, 4, end) + 5;
        bitPos += 4;
        if (log > maxLog) {
            throw new DataFormatException("Zstandard FSE accuracy log too large");
        }
        var remaining = 1 << log;
        var symbol = 0;
        while (remaining > 0) {
            if (symbol > maxSymbol) {
                throw new DataFormatException("Zstandard FSE table with too many symbols");
            }
            var nbBits = 32 - Integer.numberOfLeadingZeros(remaining + 1);
            if (bitPos + nbBits - 1 > bitEnd) {
                throw new DataFormatException("truncated Zstandard FSE table");
            }
            var value = (int) readForward(src, bitPos, nbBits, end);
            var lowMask = (1 << (nbBits - 1)) - 1;
            var threshold = (1 << nbBits) - 1 - (remaining + 1);
            if ((value & lowMask) < threshold) {
                value &= lowMask;
                bitPos += nbBits - 1;
            } else {
                if (value > lowMask) {
                    value -= threshold;
                }
                bitPos += nbBits;
            }
            if (bitPos > bitEnd) {
                throw new DataFormatException("truncated Zstandard FSE table");
            }
            var probability = value - 1;
            remaining -= probability < 0 ? 1 : probability;
            counts[symbol++] = (short) probability;
            if (probability == 0) {
                while (true) {
                    if (bitPos + 2 > bitEnd) {
                        throw new DataFormatException("truncated Zstandard FSE table");
                    }
                    var repeat = (int) readForward(src, bitPos, 2, end);
                    bitPos += 2;
                    if (symbol + repeat > maxSymbol + 1) {
                        throw new DataFormatException("Zstandard FSE table with too many symbols");
                    }
                    for (var i = 0; i < repeat; i++) {
                        counts[symbol++] = 0;
                    }
                    if (repeat != 3) {
                        break;
                    }
                }
            }
        }
        if (remaining != 0) {
            throw new DataFormatException("Zstandard FSE probabilities do not sum to the table size");
        }
        accuracy = log;
        lastSymbol = symbol - 1;
        return (int) ((bitPos + 7) >>> 3);
    }

    /**
     * Reads bits forward, little-endian, without reading past an end.
     *
     * @param src the input
     * @param bitPos the first bit
     * @param count the number of bits, at most 25
     * @param end the end of the readable input
     * @return the bits
     */
    private static long readForward(byte[] src, long bitPos, int count, int end) {
        var index = (int) (bitPos >>> 3);
        var value = 0L;
        for (var i = 0; i < 4 && index + i < end; i++) {
            value |= (long) (src[index + i] & 0xFF) << (8 * i);
        }
        return (value >>> (bitPos & 7)) & ((1L << count) - 1);
    }

    /**
     * Sets up the table of one symbol type from its compression mode.
     *
     * @param src the input
     * @param pos the position of the table description, if any
     * @param end the end of the block
     * @param mode the compression mode
     * @param kind 0 for literal lengths, 1 for offsets, 2 for match lengths
     * @return the position after the description
     * @throws DataFormatException if the description is invalid
     */
    private int table(byte[] src, int pos, int end, int mode, int kind) throws DataFormatException {
        var own = kind == 0 ? literalLengths : kind == 1 ? offsets : matchLengths;
        var maxSymbol = kind == 0 ? 35 : kind == 1 ? MAX_OFFSET_CODE : 52;
        FseTable chosen;
        switch (mode) {
            case 0 -> chosen = kind == 0 ? defaultLiteralLengths : kind == 1 ? defaultOffsets : defaultMatchLengths;
            case 1 -> {
                if (pos >= end) {
                    throw new DataFormatException("truncated Zstandard RLE sequence table");
                }
                var symbol = src[pos++] & 0xFF;
                if (symbol > maxSymbol) {
                    throw new DataFormatException("invalid Zstandard RLE sequence symbol");
                }
                own.rle(symbol);
                chosen = own;
            }
            case 2 -> {
                pos = readCounts(src, pos, end, kind == 1 ? MAX_OFFSET_LOG : MAX_LENGTH_LOG, maxSymbol);
                own.build(counts, lastSymbol, accuracy);
                chosen = own;
            }
            default -> {
                if (!sequenceTables) {
                    throw new DataFormatException("Zstandard repeat mode without a previous table");
                }
                chosen = kind == 0 ? llTable : kind == 1 ? ofTable : mlTable;
            }
        }
        switch (kind) {
            case 0 -> llTable = chosen;
            case 1 -> ofTable = chosen;
            default -> mlTable = chosen;
        }
        return pos;
    }

    /**
     * Decodes and executes the sequences of a block.
     *
     * @param src the input
     * @param start the first byte of the sequence bit stream
     * @param end the end of the block
     * @param count the number of sequences
     * @param maxBlock the largest decompressed block size
     * @param blockStart the output position of the block
     * @throws DataFormatException if the sequences are invalid
     */
    private void executeSequences(byte[] src, int start, int end, int count, int maxBlock, int blockStart)
            throws DataFormatException {
        var reader = bits;
        reader.init(src, start, end);
        var ll = llTable;
        var of = ofTable;
        var ml = mlTable;
        var llState = (int) reader.read(ll.log);
        var ofState = (int) reader.read(of.log);
        var mlState = (int) reader.read(ml.log);
        reader.reload();
        var literalPos = literalStart;
        var literalEnd = literalStart + literalCount;
        var source = literalSource;
        var r1 = rep1;
        var r2 = rep2;
        var r3 = rep3;
        var output = out;
        for (var n = 0; n < count; n++) {
            var ofCode = of.symbols[ofState];
            var mlCode = ml.symbols[mlState];
            var llCode = ll.symbols[llState];
            long offsetValue;
            if (ofCode == 0) {
                offsetValue = 1;
            } else {
                offsetValue = (1L << ofCode) + reader.read(ofCode);
                reader.reload();
            }
            var matchLength = ML_BASE[mlCode] + (int) reader.read(ML_BITS[mlCode]);
            reader.reload();
            var literalLength = LL_BASE[llCode] + (int) reader.read(LL_BITS[llCode]);
            reader.reload();
            long offset;
            if (offsetValue > 3) {
                offset = offsetValue - 3;
                r3 = r2;
                r2 = r1;
                r1 = offset;
            } else {
                var index = (int) offsetValue - (literalLength == 0 ? 0 : 1);
                if (index == 0) {
                    offset = r1;
                } else if (index == 1) {
                    offset = r2;
                    r2 = r1;
                    r1 = offset;
                } else if (index == 2) {
                    offset = r3;
                    r3 = r2;
                    r2 = r1;
                    r1 = offset;
                } else {
                    offset = r1 - 1;
                    if (offset == 0) {
                        throw new DataFormatException("Zstandard repeat offset of zero");
                    }
                    r3 = r2;
                    r2 = r1;
                    r1 = offset;
                }
            }
            if (n + 1 < count) {
                llState = ll.baselines[llState] + (int) reader.read(ll.bits[llState]);
                mlState = ml.baselines[mlState] + (int) reader.read(ml.bits[mlState]);
                reader.reload();
                ofState = of.baselines[ofState] + (int) reader.read(of.bits[ofState]);
                reader.reload();
            }
            if (literalLength > literalEnd - literalPos) {
                throw new DataFormatException("Zstandard sequence uses more literals than decoded");
            }
            if (outPos - blockStart + (long) literalLength + matchLength > maxBlock) {
                throw new DataFormatException("Zstandard block larger than the maximum block size");
            }
            var copy = Math.min(literalLength, outEnd - outPos);
            System.arraycopy(source, literalPos, output, outPos, copy);
            outPos += copy;
            literalPos += literalLength;
            if (copy < literalLength) {
                clipped = true;
                return;
            }
            if (offset > outPos - frameStart) {
                throw new DataFormatException("Zstandard offset reaches before the start of the frame");
            }
            var room = outEnd - outPos;
            if (room <= 0) {
                clipped = true;
                return;
            }
            var length = Math.min(matchLength, room);
            var from = outPos - (int) offset;
            if (offset >= length) {
                System.arraycopy(output, from, output, outPos, length);
            } else {
                for (var i = 0; i < length; i++) {
                    output[outPos + i] = output[from + i];
                }
            }
            outPos += length;
            if (length < matchLength) {
                clipped = true;
                return;
            }
        }
        if (!reader.finished()) {
            throw new DataFormatException("invalid Zstandard sequence bit stream");
        }
        rep1 = r1;
        rep2 = r2;
        rep3 = r3;
        if (outPos - blockStart + (long) (literalEnd - literalPos) > maxBlock) {
            throw new DataFormatException("Zstandard block larger than the maximum block size");
        }
        copyLiterals(literalPos, literalEnd - literalPos);
    }

    /**
     * Copies decoded literals to the output, as many as fit.
     *
     * @param from the first literal
     * @param count the number of literals
     */
    private void copyLiterals(int from, int count) {
        var copy = Math.min(count, outEnd - outPos);
        clipped |= copy < count;
        System.arraycopy(literalSource, from, out, outPos, copy);
        outPos += copy;
    }

    /**
     * Reads a little-endian 32-bit integer.
     *
     * @param src the bytes
     * @param pos the position
     * @return the value
     */
    static int intLE(byte[] src, int pos) {
        return (src[pos] & 0xFF) | (src[pos + 1] & 0xFF) << 8 | (src[pos + 2] & 0xFF) << 16 | (src[pos + 3] & 0xFF) << 24;
    }

    /**
     * Holds an FSE decoding table: the symbol, extra bits and next-state baseline of each state.
     */
    static final class FseTable {
        /** The symbol of each state. */
        final int[] symbols;

        /** The bits read to reach the next state. */
        final int[] bits;

        /** The baseline of the next state. */
        final int[] baselines;

        /** The accuracy log. */
        int log;

        /** The next free occurrence index of each symbol while building. */
        private final int[] next = new int[256];

        /**
         * Constructs a table.
         *
         * @param maxLog the largest accuracy log
         */
        FseTable(int maxLog) {
            symbols = new int[1 << maxLog];
            bits = new int[1 << maxLog];
            baselines = new int[1 << maxLog];
        }

        /**
         * Makes the table decode one symbol without reading bits.
         *
         * @param symbol the symbol every read returns
         */
        void rle(int symbol) {
            log = 0;
            symbols[0] = symbol;
            bits[0] = 0;
            baselines[0] = 0;
        }

        /**
         * Builds the table from a normalized distribution.
         *
         * @param counts the normalized counts, -1 for a probability below one
         * @param last the last symbol
         * @param accuracyLog the accuracy log
         */
        void build(short[] counts, int last, int accuracyLog) {
            log = accuracyLog;
            var size = 1 << accuracyLog;
            var high = size - 1;
            for (var s = 0; s <= last; s++) {
                if (counts[s] == -1) {
                    symbols[high--] = s;
                    next[s] = 1;
                } else {
                    next[s] = counts[s];
                }
            }
            var step = (size >>> 1) + (size >>> 3) + 3;
            var mask = size - 1;
            var position = 0;
            for (var s = 0; s <= last; s++) {
                for (var i = 0; i < counts[s]; i++) {
                    symbols[position] = s;
                    do {
                        position = (position + step) & mask;
                    } while (position > high);
                }
            }
            for (var state = 0; state < size; state++) {
                var symbol = symbols[state];
                var occurrence = next[symbol]++;
                var nb = accuracyLog - (31 - Integer.numberOfLeadingZeros(occurrence));
                bits[state] = nb;
                baselines[state] = (occurrence << nb) - size;
            }
        }
    }

    /**
     * Reads a bit stream backward, from its last byte (whose highest set bit marks the end) toward its
     * first, as Zstandard's FSE and Huffman streams are read.
     */
    static final class BackwardBits {
        /** The stream bytes. */
        private byte[] buf;

        /** The first byte of the stream. */
        private int start;

        /** The first byte of the 64-bit window. */
        private int ptr;

        /** The 64-bit window, little-endian. */
        private long container;

        /** The bits of the window already read, from its top. */
        private int consumed;

        /**
         * Starts reading a stream.
         *
         * @param src the input
         * @param from the first byte of the stream
         * @param to the end of the stream
         * @throws DataFormatException if the stream is empty or its last byte is zero
         */
        void init(byte[] src, int from, int to) throws DataFormatException {
            var length = to - from;
            if (length <= 0) {
                throw new DataFormatException("empty Zstandard bit stream");
            }
            var last = src[to - 1] & 0xFF;
            if (last == 0) {
                throw new DataFormatException("Zstandard bit stream without its end mark");
            }
            buf = src;
            start = from;
            var padding = Integer.numberOfLeadingZeros(last) - 24 + 1;
            if (length >= 8) {
                ptr = to - 8;
                container = (long) LONG.get(src, ptr);
                consumed = padding;
            } else {
                ptr = from;
                var value = 0L;
                for (var i = 0; i < length; i++) {
                    value |= (long) (src[from + i] & 0xFF) << (8 * i);
                }
                container = value;
                consumed = (8 - length) * 8 + padding;
            }
        }

        /**
         * Reads bits; past the start of the stream, zero bits are read and the stream overflows.
         *
         * @param count the number of bits, at most 57 after a reload
         * @return the bits
         */
        long read(int count) {
            if (count == 0) {
                return 0;
            }
            var value = consumed >= 64 ? 0 : (container << consumed) >>> (64 - count);
            consumed += count;
            return value;
        }

        /**
         * Returns the next bits without reading them, zero-filled past the start of the stream.
         *
         * @param shift 64 minus the number of bits
         * @return the bits
         */
        int peek(int shift) {
            return consumed >= 64 ? 0 : (int) ((container << consumed) >>> shift);
        }

        /**
         * Marks bits as read.
         *
         * @param count the number of bits
         */
        void skip(int count) {
            consumed += count;
        }

        /**
         * Moves the window toward the start of the stream so that at least 57 unread bits are in it,
         * when the stream has them.
         */
        void reload() {
            if (consumed > 64) {
                return;
            }
            var bytes = consumed >>> 3;
            if (ptr - bytes < start) {
                bytes = ptr - start;
            }
            if (bytes > 0) {
                ptr -= bytes;
                consumed -= bytes << 3;
                container = (long) LONG.get(buf, ptr);
            }
        }

        /**
         * {@return whether more bits were read than the stream holds}
         */
        boolean overflowed() {
            return ptr == start && consumed > 64;
        }

        /**
         * {@return whether every bit of the stream has been read, and no more}
         */
        boolean finished() {
            return ptr == start && consumed == 64;
        }
    }
}
