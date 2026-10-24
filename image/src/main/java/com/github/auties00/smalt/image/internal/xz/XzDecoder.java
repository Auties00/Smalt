package com.github.auties00.smalt.image.internal.xz;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;

/**
 * Decompresses XZ data held in memory into a caller buffer of known size.
 *
 * <p>The input is empty, or one or more XZ streams separated and followed by stream padding (the .xz file
 * format, version 1.2.1), or, when it does not start with the XZ magic bytes, one LZMA stream with
 * the 13-byte header of the legacy .lzma format. XZ blocks may use the LZMA2 filter preceded by up to
 * three delta and branch-call-jump filters (x86, PowerPC, IA-64, ARM, ARM-Thumb, SPARC, ARM64 and
 * RISC-V). Decoding stops when the output is full, when the input ends, or at the first error; the
 * bytes produced before that point are in the output, and {@link #produced()} counts them. When a
 * block is decoded in full, its integrity check (CRC32, CRC64 or SHA-256) and the sizes its header and
 * the stream index record are verified; the integrity checks the format reserves are skipped.
 *
 * <p>Instances are not thread-safe; an instance keeps its probability tables between calls.
 */
public final class XzDecoder {
    /** The magic bytes of an XZ stream header. */
    private static final byte[] HEADER_MAGIC = {(byte) 0xFD, '7', 'z', 'X', 'Z', 0};

    /** The filter id of LZMA2. */
    private static final int LZMA2 = 0x21;

    /** The size of the integrity check of each check type. */
    private static final int[] CHECK_SIZES = {0, 4, 4, 4, 8, 8, 8, 16, 16, 16, 32, 32, 32, 64, 64, 64};

    /** The LZMA layer. */
    private final LzmaDecoder lzma = new LzmaDecoder();

    /** The CRC32 of headers, indexes and blocks. */
    private final CRC32 crc32 = new CRC32();

    /** The SHA-256 digest of blocks, created on first use. */
    private MessageDigest sha256;

    /** The output of the current call. */
    private byte[] out;
    private int outPos;
    private int outEnd;

    /** Whether the output filled up before the data ended. */
    private boolean clipped;

    /** The bytes produced by the last call. */
    private int produced;

    /** Whether the last call failed because the input ended. */
    private boolean truncated;

    /** The filters of the current block, before LZMA2, in stream order. */
    private final int[] filterIds = new int[3];
    private final int[] filterProperties = new int[3];
    private int filterCount;

    /** The dictionary size of the current block. */
    private int dictionarySize;

    /** The blocks of the current stream: their unpadded and uncompressed sizes. */
    private long[] blockSizes = new long[16];
    private int blockCount;

    /** The output of a block with branch filters, decoded beyond the caller output. */
    private byte[] scratch = new byte[0];

    /**
     * Decompresses streams into an output range.
     *
     * @param src the input
     * @param srcOff the first input byte
     * @param srcLen the number of input bytes
     * @param dst the output
     * @param dstOff the first output byte
     * @param dstLen the room in the output, at most this many bytes are written
     * @return the number of bytes produced
     * @throws DataFormatException if the data is not valid XZ or LZMA data, uses a filter this decoder
     * lacks, or fails an integrity check; {@link #produced()} then counts the bytes written before the
     * error
     */
    public int decompress(byte[] src, int srcOff, int srcLen, byte[] dst, int dstOff, int dstLen)
            throws DataFormatException {
        out = dst;
        outPos = dstOff;
        outEnd = dstOff + dstLen;
        clipped = false;
        truncated = false;
        try {
            var end = srcOff + srcLen;
            if (srcLen >= 6 && Arrays.equals(src, srcOff, srcOff + 6, HEADER_MAGIC, 0, 6)) {
                var pos = srcOff;
                while (pos < end && !clipped) {
                    pos = stream(src, pos, end);
                    if (clipped) {
                        break;
                    }
                    var padding = pos;
                    while (pos < end && src[pos] == 0) {
                        pos++;
                    }
                    if (((pos - padding) & 3) != 0) {
                        throw new DataFormatException("XZ stream padding that is not a multiple of four bytes");
                    }
                }
            } else if (srcLen > 0) {
                lzmaAlone(src, srcOff, end);
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
     * Decodes one XZ stream.
     *
     * @param src the input
     * @param pos the stream header position
     * @param end the end of the input
     * @return the position after the stream footer
     * @throws DataFormatException if the stream is invalid
     */
    private int stream(byte[] src, int pos, int end) throws DataFormatException {
        if (end - pos < 12 || !Arrays.equals(src, pos, pos + 6, HEADER_MAGIC, 0, 6)) {
            throw new DataFormatException("not an XZ stream");
        }
        if (src[pos + 6] != 0 || (src[pos + 7] & 0xF0) != 0) {
            throw new DataFormatException("unsupported XZ stream flags");
        }
        if (crc(src, pos + 6, 2) != intLE(src, pos + 8)) {
            throw new DataFormatException("XZ stream header CRC32 mismatch");
        }
        var check = src[pos + 7] & 0x0F;
        pos += 12;
        blockCount = 0;
        while (true) {
            if (pos >= end) {
                throw truncated("truncated XZ stream");
            }
            if (src[pos] == 0) {
                break;
            }
            pos = block(src, pos, end, check);
            if (clipped) {
                return pos;
            }
        }
        var indexStart = pos;
        pos = index(src, pos, end);
        if (end - pos < 12) {
            throw truncated("truncated XZ stream footer");
        }
        if (crc(src, pos + 4, 6) != intLE(src, pos)) {
            throw new DataFormatException("XZ stream footer CRC32 mismatch");
        }
        var backward = ((intLE(src, pos + 4) & 0xFFFFFFFFL) + 1) * 4;
        if (backward != pos - indexStart) {
            throw new DataFormatException("XZ backward size does not match the index");
        }
        if (src[pos + 8] != 0 || (src[pos + 9] & 0x0F) != check || (src[pos + 9] & 0xF0) != 0
                || src[pos + 10] != 'Y' || src[pos + 11] != 'Z') {
            throw new DataFormatException("invalid XZ stream footer");
        }
        return pos + 12;
    }

    /**
     * Decodes one block.
     *
     * @param src the input
     * @param pos the block header position
     * @param end the end of the input
     * @param check the check type of the stream
     * @return the position after the block
     * @throws DataFormatException if the block is invalid
     */
    private int block(byte[] src, int pos, int end, int check) throws DataFormatException {
        var headerSize = ((src[pos] & 0xFF) + 1) * 4;
        if (end - pos < headerSize) {
            throw truncated("truncated XZ block header");
        }
        if (crc(src, pos, headerSize - 4) != intLE(src, pos + headerSize - 4)) {
            throw new DataFormatException("XZ block header CRC32 mismatch");
        }
        var flags = src[pos + 1] & 0xFF;
        if ((flags & 0x3C) != 0) {
            throw new DataFormatException("unsupported XZ block flags");
        }
        var headerEnd = pos + headerSize - 4;
        var at = new int[]{pos + 2};
        var compressedSize = (flags & 0x40) != 0 ? vli(src, at, headerEnd, false) : -1;
        var uncompressedSize = (flags & 0x80) != 0 ? vli(src, at, headerEnd, false) : -1;
        if (compressedSize == 0) {
            throw new DataFormatException("XZ block with a zero compressed size");
        }
        var filters = (flags & 3) + 1;
        filterCount = 0;
        for (var i = 0; i < filters; i++) {
            var id = vli(src, at, headerEnd, false);
            var propertiesSize = vli(src, at, headerEnd, false);
            if (propertiesSize > headerEnd - at[0]) {
                throw new DataFormatException("XZ filter properties beyond the block header");
            }
            var p = at[0];
            at[0] += (int) propertiesSize;
            var last = i == filters - 1;
            if (id == LZMA2) {
                if (!last || propertiesSize != 1) {
                    throw new DataFormatException("invalid XZ LZMA2 filter");
                }
                var bits = src[p] & 0xFF;
                if (bits > 40) {
                    throw new DataFormatException("invalid XZ LZMA2 dictionary size");
                }
                dictionarySize = bits == 40 ? Integer.MAX_VALUE
                        : (int) Math.min(Integer.MAX_VALUE, (long) (2 | (bits & 1)) << (bits / 2 + 11));
            } else if (last) {
                throw new DataFormatException("XZ block whose last filter is not LZMA2");
            } else if (id == BranchFilters.DELTA) {
                if (propertiesSize != 1) {
                    throw new DataFormatException("invalid XZ delta filter");
                }
                filterIds[filterCount] = (int) id;
                filterProperties[filterCount++] = (src[p] & 0xFF) + 1;
            } else if (id >= BranchFilters.X86 && id <= BranchFilters.RISCV) {
                if (propertiesSize != 0 && propertiesSize != 4) {
                    throw new DataFormatException("invalid XZ branch filter properties");
                }
                filterIds[filterCount] = (int) id;
                filterProperties[filterCount++] = propertiesSize == 4 ? intLE(src, p) : 0;
            } else {
                throw new DataFormatException("unsupported XZ filter 0x" + Long.toHexString(id));
            }
        }
        for (var i = at[0]; i < headerEnd; i++) {
            if (src[i] != 0) {
                throw new DataFormatException("non-zero XZ block header padding");
            }
        }
        pos += headerSize;
        var branch = false;
        for (var i = 0; i < filterCount; i++) {
            branch |= filterIds[i] != BranchFilters.DELTA;
        }
        var dataStart = pos;
        var writeStart = outPos;
        int decoded;
        if (branch) {
            var room = outEnd - outPos;
            var capacity = (int) Math.min(Integer.MAX_VALUE - 64L, (long) room + 16);
            if (uncompressedSize >= 0) {
                capacity = (int) Math.min(capacity, uncompressedSize);
            }
            if (scratch.length < capacity) {
                scratch = new byte[capacity];
            }
            var saved = out;
            var savedPos = outPos;
            var savedEnd = outEnd;
            out = scratch;
            outPos = 0;
            outEnd = capacity;
            var savedClipped = clipped;
            var complete = false;
            try {
                pos = lzma2(src, pos, end);
                complete = true;
            } finally {
                decoded = outPos;
                var blockClipped = clipped;
                out = saved;
                outPos = savedPos;
                outEnd = savedEnd;
                clipped = savedClipped;
                for (var i = filterCount - 1; i >= 0; i--) {
                    BranchFilters.decode(filterIds[i], filterProperties[i], scratch, 0, decoded);
                }
                // the last bytes of a block that ended in an error may lack the input that converts them
                var copy = Math.min(complete ? decoded : Math.max(0, decoded - 16), room);
                System.arraycopy(scratch, 0, out, outPos, copy);
                outPos += copy;
                clipped = blockClipped || decoded > room;
            }
        } else {
            try {
                pos = lzma2(src, pos, end);
            } finally {
                decoded = outPos - writeStart;
                for (var i = filterCount - 1; i >= 0; i--) {
                    BranchFilters.decode(filterIds[i], filterProperties[i], out, writeStart, decoded);
                }
            }
        }
        if (clipped) {
            return pos;
        }
        var compressed = pos - dataStart;
        if (compressedSize >= 0 && compressedSize != compressed) {
            throw new DataFormatException("XZ block compressed size does not match its header");
        }
        if (uncompressedSize >= 0 && uncompressedSize != decoded) {
            throw new DataFormatException("XZ block uncompressed size does not match its header");
        }
        while (((pos - dataStart) & 3) != 0) {
            if (pos >= end || src[pos] != 0) {
                throw new DataFormatException("invalid XZ block padding");
            }
            pos++;
        }
        var checkSize = CHECK_SIZES[check];
        if (end - pos < checkSize) {
            throw truncated("truncated XZ block check");
        }
        verify(check, out, writeStart, decoded, src, pos);
        pos += checkSize;
        if (blockCount * 2 == blockSizes.length) {
            blockSizes = Arrays.copyOf(blockSizes, blockSizes.length * 2);
        }
        blockSizes[2 * blockCount] = (long) headerSize + compressed + checkSize;
        blockSizes[2 * blockCount + 1] = decoded;
        blockCount++;
        return pos;
    }

    /**
     * Decodes LZMA2 chunks until the end marker into the output.
     *
     * @param src the input
     * @param pos the first chunk
     * @param end the end of the input
     * @return the position after the end marker, or any position when the output filled up
     * @throws DataFormatException if the data is invalid
     */
    private int lzma2(byte[] src, int pos, int end) throws DataFormatException {
        var needDictionaryReset = true;
        var needProperties = true;
        var dictStart = outPos;
        while (true) {
            if (pos >= end) {
                throw truncated("truncated LZMA2 data");
            }
            var control = src[pos++] & 0xFF;
            if (control == 0) {
                return pos;
            }
            if (control >= 0xE0 || control == 1) {
                needProperties = true;
                needDictionaryReset = false;
                dictStart = outPos;
            } else if (needDictionaryReset) {
                throw new DataFormatException("LZMA2 data without a dictionary reset");
            }
            if (control >= 0x80) {
                if (end - pos < 4) {
                    throw truncated("truncated LZMA2 chunk header");
                }
                var uncompressed = ((control & 0x1F) << 16) + ((src[pos] & 0xFF) << 8) + (src[pos + 1] & 0xFF) + 1;
                var compressed = ((src[pos + 2] & 0xFF) << 8) + (src[pos + 3] & 0xFF) + 1;
                pos += 4;
                if (control >= 0xC0) {
                    if (pos >= end) {
                        throw truncated("truncated LZMA2 chunk header");
                    }
                    var properties = src[pos++] & 0xFF;
                    if (properties >= 9 * 5 * 5) {
                        throw new DataFormatException("invalid LZMA2 properties");
                    }
                    var lc = properties % 9;
                    var lp = (properties / 9) % 5;
                    var pb = properties / 45;
                    if (lc + lp > 4) {
                        throw new DataFormatException("invalid LZMA2 properties");
                    }
                    needProperties = false;
                    lzma.properties(lc, lp, pb);
                    lzma.reset();
                } else if (needProperties) {
                    throw new DataFormatException("LZMA2 chunk without properties");
                } else if (control >= 0xA0) {
                    lzma.reset();
                }
                var available = Math.min(compressed, end - pos);
                if (available < 5) {
                    throw available < compressed ? truncated("truncated LZMA2 chunk")
                            : new DataFormatException("LZMA2 chunk shorter than its range coder header");
                }
                lzma.output(out, dictStart, dictionarySize);
                lzma.start(src, pos, pos + available);
                var chunkEnd = (long) outPos + uncompressed;
                var limit = (int) Math.min(chunkEnd, outEnd);
                try {
                    outPos = lzma.decode(outPos, limit, (int) Math.min(chunkEnd, Integer.MAX_VALUE), false);
                } catch (DataFormatException exception) {
                    outPos = lzma.position();
                    if (available < compressed) {
                        throw truncated("truncated LZMA2 chunk");
                    }
                    throw exception;
                }
                if (available < compressed && limit == chunkEnd) {
                    throw truncated("truncated LZMA2 chunk");
                }
                if (limit < chunkEnd) {
                    clipped = true;
                    return pos;
                }
                if (lzma.inputPosition() != pos + compressed || !lzma.finished()) {
                    throw new DataFormatException("LZMA2 chunk does not end with its data");
                }
                pos += compressed;
            } else {
                if (control > 2) {
                    throw new DataFormatException("invalid LZMA2 control byte");
                }
                if (end - pos < 2) {
                    throw truncated("truncated LZMA2 chunk header");
                }
                var size = ((src[pos] & 0xFF) << 8) + (src[pos + 1] & 0xFF) + 1;
                pos += 2;
                var available = Math.min(size, end - pos);
                var copy = Math.min(available, outEnd - outPos);
                System.arraycopy(src, pos, out, outPos, copy);
                outPos += copy;
                if (copy < size) {
                    if (copy == available && available < size) {
                        throw truncated("truncated LZMA2 uncompressed chunk");
                    }
                    clipped = true;
                    return pos;
                }
                pos += size;
            }
        }
    }

    /**
     * Decodes data of the legacy .lzma format.
     *
     * @param src the input
     * @param pos the header position
     * @param end the end of the input
     * @throws DataFormatException if the data is invalid
     */
    private void lzmaAlone(byte[] src, int pos, int end) throws DataFormatException {
        if (end - pos < 13) {
            throw new DataFormatException("not XZ or LZMA data");
        }
        var properties = src[pos] & 0xFF;
        var dictionary = intLE(src, pos + 1);
        var size = (intLE(src, pos + 5) & 0xFFFFFFFFL) | (long) intLE(src, pos + 9) << 32;
        var d = dictionary & 0xFFFFFFFFL;
        var validDictionary = d == 0xFFFFFFFFL || Long.bitCount(d) == 1
                || Long.bitCount(d) == 2 && (d & (d >>> 1)) != 0;
        if (properties >= 9 * 5 * 5 || !validDictionary || size != -1 && (size < 0 || size >= 1L << 38)) {
            throw new DataFormatException("not XZ or LZMA data");
        }
        lzma.properties(properties % 9, (properties / 9) % 5, properties / 45);
        lzma.reset();
        var dictSize = dictionary == -1 ? Integer.MAX_VALUE : (int) Math.min(Integer.MAX_VALUE, ((dictionary & 0xFFFFFFFFL) + 15) & ~15L);
        lzma.output(out, outPos, Math.max(4096, dictSize));
        lzma.start(src, pos + 13, end);
        var dataEnd = size == -1 ? Integer.MAX_VALUE : (int) Math.min(Integer.MAX_VALUE, outPos + size);
        var limit = Math.min(dataEnd, outEnd);
        int reached;
        try {
            reached = lzma.decode(outPos, limit, dataEnd, true);
        } catch (DataFormatException exception) {
            outPos = lzma.position();
            if (exception.getMessage().startsWith("truncated")) {
                truncated = true;
            }
            throw exception;
        }
        outPos = reached;
        if (reached == limit) {
            clipped = limit < dataEnd;
            return;
        }
        if (size != -1) {
            throw new DataFormatException("LZMA end marker before the end of the data");
        }
        if (!lzma.finished()) {
            throw new DataFormatException("LZMA data does not end cleanly after its end marker");
        }
    }

    /**
     * Reads the index of a stream and compares it with the blocks.
     *
     * @param src the input
     * @param pos the index indicator position
     * @param end the end of the input
     * @return the position after the index
     * @throws DataFormatException if the index is invalid or does not describe the blocks
     */
    private int index(byte[] src, int pos, int end) throws DataFormatException {
        var start = pos;
        var at = new int[]{pos + 1};
        var records = vli(src, at, end, true);
        if (records != blockCount) {
            throw new DataFormatException("the XZ index does not match the number of blocks");
        }
        for (var i = 0; i < blockCount; i++) {
            var unpadded = vli(src, at, end, true);
            var uncompressed = vli(src, at, end, true);
            if (unpadded != blockSizes[2 * i] || uncompressed != blockSizes[2 * i + 1]) {
                throw new DataFormatException("the XZ index does not match block " + i);
            }
        }
        pos = at[0];
        while (((pos - start) & 3) != 0) {
            if (pos >= end || src[pos] != 0) {
                throw new DataFormatException("invalid XZ index padding");
            }
            pos++;
        }
        if (end - pos < 4) {
            throw truncated("truncated XZ index");
        }
        if (crc(src, start, pos - start) != intLE(src, pos)) {
            throw new DataFormatException("XZ index CRC32 mismatch");
        }
        return pos + 4;
    }

    /**
     * Verifies the integrity check of a block.
     *
     * @param check the check type
     * @param data the uncompressed block
     * @param offset the first byte
     * @param length the number of bytes
     * @param src the input
     * @param pos the check position
     * @throws DataFormatException if the check does not match
     */
    private void verify(int check, byte[] data, int offset, int length, byte[] src, int pos)
            throws DataFormatException {
        switch (check) {
            case 1 -> {
                if (crc(data, offset, length) != intLE(src, pos)) {
                    throw new DataFormatException("XZ block CRC32 mismatch");
                }
            }
            case 4 -> {
                var value = Crc64.compute(data, offset, length);
                var stored = (intLE(src, pos) & 0xFFFFFFFFL) | (long) intLE(src, pos + 4) << 32;
                if (value != stored) {
                    throw new DataFormatException("XZ block CRC64 mismatch");
                }
            }
            case 10 -> {
                if (sha256 == null) {
                    try {
                        sha256 = MessageDigest.getInstance("SHA-256");
                    } catch (NoSuchAlgorithmException exception) {
                        throw new IllegalStateException(exception);
                    }
                }
                sha256.reset();
                sha256.update(data, offset, length);
                if (!Arrays.equals(sha256.digest(), 0, 32, src, pos, pos + 32)) {
                    throw new DataFormatException("XZ block SHA-256 mismatch");
                }
            }
            default -> {
            }
        }
    }

    /**
     * Reads a variable-length integer of the XZ format.
     *
     * @param src the input
     * @param at the position, advanced past the integer
     * @param end the end of the readable input
     * @param inputEnd whether {@code end} is the end of the input, so that reaching it means the input
     * ended early rather than that a complete header is invalid
     * @return the value
     * @throws DataFormatException if the integer is truncated, longer than nine bytes or not minimal
     */
    private long vli(byte[] src, int[] at, int end, boolean inputEnd) throws DataFormatException {
        var value = 0L;
        for (var i = 0; i < 9; i++) {
            if (at[0] >= end) {
                throw inputEnd ? truncated("truncated XZ integer") : new DataFormatException("XZ integer beyond its header");
            }
            var b = src[at[0]++] & 0xFF;
            value |= (long) (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) {
                if (b == 0 && i > 0) {
                    throw new DataFormatException("non-minimal XZ integer");
                }
                return value;
            }
        }
        throw new DataFormatException("XZ integer longer than nine bytes");
    }

    private int crc(byte[] data, int offset, int length) {
        crc32.reset();
        crc32.update(data, offset, length);
        return (int) crc32.getValue();
    }

    private static int intLE(byte[] src, int pos) {
        return (src[pos] & 0xFF) | (src[pos + 1] & 0xFF) << 8 | (src[pos + 2] & 0xFF) << 16 | (src[pos + 3] & 0xFF) << 24;
    }
}
