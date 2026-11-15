/* Ports libwebp 1.6.0 (src/dec/vp8l_dec.c, src/dsp/lossless.c). Copyright 2010 Google Inc.; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.image.webp.internal.lossless;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.webp.internal.alpha.AlphaFilters;

import com.github.auties00.smalt.internal.limits.LimitChecks;
import java.util.Arrays;

/**
 * Decodes WebP lossless (VP8L) bitstreams: full images to RGBA and headerless alpha streams to an
 * 8-bit plane.
 *
 * <p>The decoder reads the transforms (predictor, cross-color, subtract-green and color
 * indexing with pixel bundling), the color cache, the entropy image of meta prefix codes and the
 * canonical prefix codes, then decodes the LZ77-coded pixels with two-level table lookups. Rows
 * are inverse-transformed and converted in batches of 16 while decoding, so the transformed
 * pixels stay in cache. Every size declared by the stream is checked against the
 * {@link MediaLimits} before its buffer is allocated, and every structural violation raises
 * {@link MediaException.Malformed}.
 *
 * @implNote This implementation follows libwebp's {@code src/dec/vp8l_dec.c} and
 * {@code src/dsp/lossless.c} statement for statement where the output depends on it:
 * the acceptance rules for code lengths and tables, the expansion of the color map to its full
 * bundled size with a black tail, the treatment of predictor modes 14 and 15 as black, the
 * 120-entry distance map, and the end-of-stream rule (a stream shorter than 8 bytes is read as if
 * zero-padded to 8 bytes, as libwebp's bit reader does). Truncation is detected at the end of
 * each row rather than after each symbol; because every symbol advances the pixel position, a
 * truncated stream is rejected either way.
 */
public final class Vp8lDecoder {
    /**
     * The signature byte of a VP8L header.
     */
    public static final int SIGNATURE = 0x2F;

    /**
     * The number of bytes of a VP8L header: signature, dimensions, alpha hint and version.
     */
    public static final int HEADER_SIZE = 5;

    /**
     * The number of green literal codes.
     */
    private static final int NUM_LITERAL_CODES = 256;

    /**
     * The number of length prefix codes.
     */
    private static final int NUM_LENGTH_CODES = 24;

    /**
     * The number of distance prefix codes.
     */
    private static final int NUM_DISTANCE_CODES = 40;

    /**
     * The largest color cache width in bits.
     */
    private static final int MAX_CACHE_BITS = 11;

    /**
     * The index width of root lookup tables.
     */
    private static final int HUFFMAN_TABLE_BITS = 8;

    /**
     * The index width of the code-length code table.
     */
    private static final int LENGTHS_TABLE_BITS = 7;

    /**
     * The number of code-length code symbols.
     */
    private static final int NUM_CODE_LENGTH_CODES = 19;

    /**
     * The first code length value that is a repeat code.
     */
    private static final int CODE_LENGTH_LITERALS = 16;

    /**
     * The code length value repeating the previous non-zero length.
     */
    private static final int CODE_LENGTH_REPEAT_CODE = 16;

    /**
     * The code length used as "previous" before any non-zero length is read.
     */
    private static final int DEFAULT_CODE_LENGTH = 8;

    /**
     * The extra bits of the three repeat codes 16, 17 and 18.
     */
    private static final int[] CODE_LENGTH_EXTRA_BITS = {2, 3, 7};

    /**
     * The repeat offsets of the three repeat codes 16, 17 and 18.
     */
    private static final int[] CODE_LENGTH_REPEAT_OFFSETS = {3, 3, 11};

    /**
     * The order in which code-length code lengths are stored (RFC 9649, section 3.7.2.1.2).
     */
    private static final int[] CODE_LENGTH_CODE_ORDER = {17, 18, 0, 1, 2, 3, 4, 5, 16, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};

    /**
     * The alphabet sizes of the five codes of a prefix code group, without color cache codes:
     * green (with length prefixes), red, blue, alpha and distance.
     */
    private static final int[] ALPHABET_SIZE = {NUM_LITERAL_CODES + NUM_LENGTH_CODES, NUM_LITERAL_CODES,
            NUM_LITERAL_CODES, NUM_LITERAL_CODES, NUM_DISTANCE_CODES};

    /**
     * Whether each of the five codes of a group codes a literal channel other than green.
     */
    private static final boolean[] LITERAL_MAP = {false, true, true, true, false};

    /**
     * The index of the green code in a group.
     */
    private static final int GREEN = 0;

    /**
     * The index of the red code in a group.
     */
    private static final int RED = 1;

    /**
     * The index of the blue code in a group.
     */
    private static final int BLUE = 2;

    /**
     * The index of the alpha code in a group.
     */
    private static final int ALPHA = 3;

    /**
     * The copy length below which backward references are copied by a plain loop rather than
     * through {@link System#arraycopy} or {@link Arrays#fill}, whose call overhead dominates
     * short runs.
     */
    private static final int SHORT_COPY = 16;

    /**
     * The distance map of the 120 short plane codes: {@code yoffset << 4 | (8 - xoffset)} (RFC
     * 9649, section 4.2.2).
     */
    private static final int[] CODE_TO_PLANE = {
            0x18, 0x07, 0x17, 0x19, 0x28, 0x06, 0x27, 0x29, 0x16, 0x1a,
            0x26, 0x2a, 0x38, 0x05, 0x37, 0x39, 0x15, 0x1b, 0x36, 0x3a,
            0x25, 0x2b, 0x48, 0x04, 0x47, 0x49, 0x14, 0x1c, 0x35, 0x3b,
            0x46, 0x4a, 0x24, 0x2c, 0x58, 0x45, 0x4b, 0x34, 0x3c, 0x03,
            0x57, 0x59, 0x13, 0x1d, 0x56, 0x5a, 0x23, 0x2d, 0x44, 0x4c,
            0x55, 0x5b, 0x33, 0x3d, 0x68, 0x02, 0x67, 0x69, 0x12, 0x1e,
            0x66, 0x6a, 0x22, 0x2e, 0x54, 0x5c, 0x43, 0x4d, 0x65, 0x6b,
            0x32, 0x3e, 0x78, 0x01, 0x77, 0x79, 0x53, 0x5d, 0x11, 0x1f,
            0x64, 0x6c, 0x42, 0x4e, 0x76, 0x7a, 0x21, 0x2f, 0x75, 0x7b,
            0x31, 0x3f, 0x63, 0x6d, 0x52, 0x5e, 0x00, 0x74, 0x7c, 0x41,
            0x4f, 0x10, 0x20, 0x62, 0x6e, 0x30, 0x73, 0x7d, 0x51, 0x5f,
            0x40, 0x72, 0x7e, 0x61, 0x6f, 0x50, 0x71, 0x7f, 0x60, 0x70
    };

    /**
     * The number of rows inverse-transformed and emitted at a time.
     */
    private static final int NUM_ARGB_CACHE_ROWS = 16;

    /**
     * The index width of the packed literal table.
     */
    private static final int PACKED_BITS = 6;

    /**
     * The marker added to the bit count of packed entries that hold a non-literal green symbol.
     */
    private static final int BITS_SPECIAL_MARKER = 0x100;

    /**
     * The multiplier of the color cache hash (RFC 9649, section 4.2.3).
     */
    private static final int HASH_MUL = 0x1E35A7BD;

    /**
     * The transform type of the predictor transform.
     */
    private static final int PREDICTOR_TRANSFORM = 0;

    /**
     * The transform type of the cross-color transform.
     */
    private static final int CROSS_COLOR_TRANSFORM = 1;

    /**
     * The transform type of the subtract-green transform.
     */
    private static final int SUBTRACT_GREEN_TRANSFORM = 2;

    /**
     * The transform type of the color indexing transform.
     */
    private static final int COLOR_INDEXING_TRANSFORM = 3;

    /**
     * The pixel kernels.
     */
    private static final LosslessDsp DSP = LosslessDsp.INSTANCE;

    /**
     * The bit reader over the stream.
     */
    private final Vp8lBitReader br;

    /**
     * The resource bounds.
     */
    private final MediaLimits limits;

    /**
     * The transforms in the order they were read.
     */
    private final Transform[] transforms = new Transform[4];

    /**
     * The number of transforms read.
     */
    private int numTransforms;

    /**
     * The bit set of transform types read, to reject repeated types.
     */
    private int transformsSeen;

    /**
     * The scratch code lengths, sized for the largest alphabet.
     */
    private final int[] codeLengths = new int[NUM_LITERAL_CODES + NUM_LENGTH_CODES + (1 << MAX_CACHE_BITS)];

    /**
     * The scratch symbol order used while filling tables.
     */
    private final int[] sorted = new int[NUM_LITERAL_CODES + NUM_LENGTH_CODES + (1 << MAX_CACHE_BITS)];

    /**
     * The width of the entropy-coded (transformed) main image.
     */
    private int codedWidth;

    /**
     * The width of the output image.
     */
    private int outWidth;

    /**
     * The entropy-coded pixels of the main image.
     */
    private int[] pixels;

    /**
     * The scratch rows for inverse transforms: one row of top context followed by
     * {@link #NUM_ARGB_CACHE_ROWS} rows, each {@link #outWidth} pixels wide.
     */
    private int[] argbCache;

    /**
     * The index in {@link #argbCache} of the first cached row.
     */
    private int cacheStart;

    /**
     * The first row not yet inverse-transformed and emitted.
     */
    private int lastRow;

    /**
     * The RGBA destination of the main image, or {@code null} when decoding alpha.
     */
    private byte[] rgbaOut;

    /**
     * The index in {@link #rgbaOut} of the first byte of the image.
     */
    private int rgbaOffset;

    /**
     * The distance in bytes between rows of {@link #rgbaOut}.
     */
    private int rgbaStride;

    /**
     * The alpha plane destination, or {@code null} when decoding RGBA.
     */
    private byte[] alphaOut;

    /**
     * The number of rows of the main image decoded, from the top; the pixels below them are not decoded.
     */
    private int rowLimit = Integer.MAX_VALUE;

    /**
     * The alpha filter method applied after extracting the alpha plane.
     */
    private int alphaFilter;

    /**
     * The index in {@link #alphaOut} of the previous unfiltered row, or {@code -1} before the first.
     */
    private int alphaPrevLine = -1;

    /**
     * Constructs a decoder over a stream.
     *
     * @param data the array holding the stream
     * @param offset the index of the first byte
     * @param length the number of bytes
     * @param limits the resource bounds
     */
    private Vp8lDecoder(byte[] data, int offset, int length, MediaLimits limits) {
        this.br = new Vp8lBitReader(data, offset, length);
        this.limits = limits;
    }

    /**
     * Parses a VP8L header.
     *
     * @param data the array holding the stream
     * @param offset the index of the first byte of the stream
     * @param length the number of bytes of the stream
     * @return {@code {width, height, alphaHint}}
     * @throws MediaException.Malformed if the header is truncated, has a wrong signature or an
     * unknown version
     */
    public static int[] readHeader(byte[] data, int offset, int length) {
        if (length < HEADER_SIZE) {
            throw new MediaException.Malformed("VP8L stream of " + length + " bytes is shorter than its header");
        }
        if ((data[offset] & 0xFF) != SIGNATURE) {
            throw new MediaException.Malformed("VP8L signature missing");
        }
        if (((data[offset + 4] & 0xFF) >>> 5) != 0) {
            throw new MediaException.Malformed("VP8L version " + ((data[offset + 4] & 0xFF) >>> 5) + " is not 0");
        }
        var bits = (data[offset + 1] & 0xFF) | (data[offset + 2] & 0xFF) << 8 | (data[offset + 3] & 0xFF) << 16
                | (data[offset + 4] & 0xFF) << 24;
        var width = (bits & 0x3FFF) + 1;
        var height = ((bits >>> 14) & 0x3FFF) + 1;
        var alpha = (bits >>> 28) & 1;
        return new int[]{width, height, alpha};
    }

    /**
     * Decodes a complete VP8L stream (with its 5-byte header) to a new packed RGBA array.
     *
     * @param data the array holding the stream
     * @param offset the index of the first byte of the stream
     * @param length the number of bytes of the stream
     * @param limits the resource bounds
     * @return the decoded image
     * @throws MediaException.Malformed if the stream is invalid or truncated
     * @throws MediaException.LimitExceeded if a declared size exceeds {@code limits}
     */
    public static Result decodeRgba(byte[] data, int offset, int length, MediaLimits limits) {
        return decodeRgba(data, offset, length, limits, Integer.MAX_VALUE);
    }

    /**
     * Decodes the top rows of a complete VP8L stream (with its 5-byte header) to a new packed RGBA array.
     *
     * <p>The pixels below the rows are not decoded, so damage there goes undetected; the rows equal those of the
     * whole decode, and the limits bound them rather than the whole image.
     *
     * @param data the array holding the stream
     * @param offset the index of the first byte of the stream
     * @param length the number of bytes of the stream
     * @param limits the resource bounds
     * @param rows the number of rows to decode, positive
     * @return the decoded rows, as an image of {@code min(rows, height)} rows
     * @throws MediaException.Malformed if the stream is invalid or truncated
     * @throws MediaException.LimitExceeded if a declared size exceeds {@code limits}
     */
    public static Result decodeRgba(byte[] data, int offset, int length, MediaLimits limits, int rows) {
        var header = readHeader(data, offset, length);
        var shown = Math.min(rows, header[1]);
        LimitChecks.checkPicture(limits, header[0], shown);
        LimitChecks.checkAllocation(limits, (long) header[0] * shown * 4);
        var rgba = new byte[header[0] * shown * 4];
        decodeRgbaInto(data, offset, length, limits, rgba, 0, header[0] * 4, shown);
        return new Result(header[0], shown, header[2] != 0, rgba);
    }

    /**
     * Decodes a complete VP8L stream (with its 5-byte header) into a region of an RGBA buffer,
     * overwriting every pixel of the region.
     *
     * @param data the array holding the stream
     * @param offset the index of the first byte of the stream
     * @param length the number of bytes of the stream
     * @param limits the resource bounds
     * @param dst the RGBA destination
     * @param dstOffset the index of the first byte of the region
     * @param dstStride the distance in bytes between destination rows
     * @return {@code {width, height, alphaHint}} of the stream
     * @throws MediaException.Malformed if the stream is invalid or truncated
     * @throws MediaException.LimitExceeded if a declared size exceeds {@code limits}
     */
    public static int[] decodeRgbaInto(byte[] data, int offset, int length, MediaLimits limits,
                                       byte[] dst, int dstOffset, int dstStride) {
        return decodeRgbaInto(data, offset, length, limits, dst, dstOffset, dstStride, Integer.MAX_VALUE);
    }

    /**
     * Decodes the top rows of a complete VP8L stream into a region of an RGBA buffer.
     *
     * @param data the array holding the stream
     * @param offset the index of the first byte of the stream
     * @param length the number of bytes of the stream
     * @param limits the resource bounds
     * @param dst the RGBA destination
     * @param dstOffset the index of the first byte of the region
     * @param dstStride the distance in bytes between destination rows
     * @param rows the number of rows to decode, positive
     * @return {@code {width, height, alphaHint}} of the stream
     * @throws MediaException.Malformed if the stream is invalid or truncated
     * @throws MediaException.LimitExceeded if a declared size exceeds {@code limits}
     */
    private static int[] decodeRgbaInto(byte[] data, int offset, int length, MediaLimits limits,
                                        byte[] dst, int dstOffset, int dstStride, int rows) {
        var header = readHeader(data, offset, length);
        var width = header[0];
        var height = header[1];
        LimitChecks.checkPicture(limits, width, Math.min(rows, height));
        var decoder = new Vp8lDecoder(data, offset, length, limits);
        decoder.rowLimit = rows;
        decoder.br.read(8);
        decoder.br.read(14);
        decoder.br.read(14);
        decoder.br.read(4);
        decoder.rgbaOut = dst;
        decoder.rgbaOffset = dstOffset;
        decoder.rgbaStride = dstStride;
        decoder.decodeMain(width, height);
        return header;
    }

    /**
     * Decodes a headerless VP8L stream holding an alpha plane in its green channel, as stored in
     * an {@code ALPH} chunk, and applies the alpha filter.
     *
     * @param data the array holding the stream
     * @param offset the index of the first byte of the stream
     * @param length the number of bytes of the stream
     * @param width the image width
     * @param height the image height
     * @param filter the filter method from the {@code ALPH} header, from 0 to 3
     * @param out the destination plane of {@code width * height} bytes
     * @param limits the resource bounds
     * @throws MediaException.Malformed if the stream is invalid or truncated
     * @throws MediaException.LimitExceeded if a declared size exceeds {@code limits}
     */
    public static void decodeAlpha(byte[] data, int offset, int length, int width, int height, int filter,
                                   byte[] out, MediaLimits limits) {
        decodeAlpha(data, offset, length, width, height, filter, out, limits, height);
    }

    /**
     * Decodes the top rows of a headerless VP8L stream holding an alpha plane, as
     * {@link #decodeAlpha(byte[], int, int, int, int, int, byte[], MediaLimits)} decodes all of them.
     *
     * @param data the array holding the stream
     * @param offset the index of the first byte of the stream
     * @param length the number of bytes of the stream
     * @param width the image width
     * @param height the image height
     * @param filter the filter method from the {@code ALPH} header, from 0 to 3
     * @param out the destination plane of {@code width * rows} bytes
     * @param limits the resource bounds
     * @param rows the number of rows to decode, from 1 to {@code height}
     * @throws MediaException.Malformed if the stream is invalid or truncated
     * @throws MediaException.LimitExceeded if a declared size exceeds {@code limits}
     */
    public static void decodeAlpha(byte[] data, int offset, int length, int width, int height, int filter,
                                   byte[] out, MediaLimits limits, int rows) {
        var decoder = new Vp8lDecoder(data, offset, length, limits);
        decoder.rowLimit = rows;
        decoder.alphaOut = out;
        decoder.alphaFilter = filter;
        decoder.decodeMain(width, height);
    }

    /**
     * Decodes the main image stream: transforms, prefix codes and pixels.
     *
     * @param width the image width
     * @param height the image height
     */
    private void decodeMain(int width, int height) {
        var xsize = width;
        while (br.readBit() == 1) {
            xsize = readTransform(xsize, height);
        }
        var cacheBits = readColorCacheBits();
        var hdr = readHuffmanCodes(xsize, height, cacheBits, true);
        checkNotOverrun();
        codedWidth = xsize;
        outWidth = width;
        var rows = Math.min(rowLimit, height);
        LimitChecks.checkAllocation(limits, ((long) xsize * rows + (long) width * (NUM_ARGB_CACHE_ROWS + 1)) * 4);
        pixels = new int[xsize * rows];
        argbCache = new int[width * (NUM_ARGB_CACHE_ROWS + 1)];
        cacheStart = width;
        decodeImageData(pixels, xsize, height, hdr, true, rows);
    }

    /**
     * Reads the color cache flag and width.
     *
     * @return the color cache width in bits, or {@code 0} without a cache
     */
    private int readColorCacheBits() {
        if (br.readBit() == 0) {
            return 0;
        }
        var bits = br.read(4);
        if (bits < 1 || bits > MAX_CACHE_BITS) {
            throw new MediaException.Malformed("invalid VP8L color cache size " + bits);
        }
        return bits;
    }

    /**
     * Decodes an entropy-coded sub-image (transform data or entropy image), which has no
     * transforms and no meta prefix codes.
     *
     * @param xsize the sub-image width
     * @param ysize the sub-image height
     * @return the decoded pixels
     */
    private int[] decodeSubImage(int xsize, int ysize) {
        var cacheBits = readColorCacheBits();
        var hdr = readHuffmanCodes(xsize, ysize, cacheBits, false);
        checkNotOverrun();
        LimitChecks.checkAllocation(limits, (long) xsize * ysize * 4);
        var data = new int[xsize * ysize];
        decodeImageData(data, xsize, ysize, hdr, false);
        return data;
    }

    /**
     * Reads one transform and its data.
     *
     * @param xsize the current image width
     * @param ysize the image height
     * @return the image width after this transform (narrower for bundled color indexing)
     */
    private int readTransform(int xsize, int ysize) {
        var type = br.read(2);
        if ((transformsSeen & (1 << type)) != 0) {
            throw new MediaException.Malformed("VP8L transform " + type + " appears twice");
        }
        transformsSeen |= 1 << type;
        var transform = new Transform(type, xsize, ysize);
        transforms[numTransforms++] = transform;
        switch (type) {
            case PREDICTOR_TRANSFORM, CROSS_COLOR_TRANSFORM -> {
                transform.bits = 2 + br.read(3);
                transform.data = decodeSubImage(subSampleSize(xsize, transform.bits), subSampleSize(ysize, transform.bits));
                return xsize;
            }
            case COLOR_INDEXING_TRANSFORM -> {
                var numColors = br.read(8) + 1;
                var bits = numColors > 16 ? 0 : numColors > 4 ? 1 : numColors > 2 ? 2 : 3;
                transform.bits = bits;
                var colors = decodeSubImage(numColors, 1);
                transform.data = expandColorMap(colors, numColors, bits);
                return subSampleSize(xsize, bits);
            }
            default -> {
                return xsize;
            }
        }
    }

    /**
     * Undoes the delta coding of a color map and extends it to every index a bundled pixel can
     * hold, filling the tail with transparent black.
     *
     * @param colors the delta-coded colors
     * @param numColors the number of colors
     * @param bits the bundling width in bits
     * @return the color map of {@code 1 << (8 >> bits)} entries
     */
    private static int[] expandColorMap(int[] colors, int numColors, int bits) {
        var finalNumColors = 1 << (8 >> bits);
        var map = new int[finalNumColors];
        map[0] = colors[0];
        for (var i = 1; i < numColors; i++) {
            map[i] = ScalarLosslessDsp.addPixels(colors[i], map[i - 1]);
        }
        return map;
    }

    /**
     * Reads the prefix codes of an image: the optional entropy image, then one group of five
     * codes per distinct meta code.
     *
     * @param xsize the image width
     * @param ysize the image height
     * @param cacheBits the color cache width in bits
     * @param allowRecursion whether meta prefix codes may be present (only in the main image)
     * @return the decoding metadata
     */
    private Metadata readHuffmanCodes(int xsize, int ysize, int cacheBits, boolean allowRecursion) {
        var hdr = new Metadata();
        int[] huffmanImage = null;
        var numGroups = 1;
        var numGroupsMax = 1;
        int[] mapping = null;
        if (allowRecursion && br.readBit() == 1) {
            var precision = 2 + br.read(3);
            var hxsize = subSampleSize(xsize, precision);
            var hysize = subSampleSize(ysize, precision);
            var hpixels = hxsize * hysize;
            huffmanImage = decodeSubImage(hxsize, hysize);
            hdr.huffmanBits = precision;
            for (var i = 0; i < hpixels; i++) {
                var group = (huffmanImage[i] >>> 8) & 0xFFFF;
                huffmanImage[i] = group;
                if (group >= numGroupsMax) {
                    numGroupsMax = group + 1;
                }
            }
            if (numGroupsMax > 1000 || numGroupsMax > xsize * ysize) {
                mapping = new int[numGroupsMax];
                Arrays.fill(mapping, -1);
                numGroups = 0;
                for (var i = 0; i < hpixels; i++) {
                    var group = huffmanImage[i];
                    if (mapping[group] == -1) {
                        mapping[group] = numGroups++;
                    }
                    huffmanImage[i] = mapping[group];
                }
            } else {
                numGroups = numGroupsMax;
            }
        }
        checkNotOverrun();
        hdr.huffmanImage = huffmanImage;
        hdr.huffmanXSize = subSampleSize(xsize, hdr.huffmanBits);
        hdr.huffmanMask = hdr.huffmanBits == 0 ? ~0 : (1 << hdr.huffmanBits) - 1;
        hdr.groups = new HTreeGroup[numGroups];
        hdr.tables = new int[Math.min(numGroups, 64) * 4096];
        var alphabetExtra = cacheBits > 0 ? 1 << cacheBits : 0;
        for (var i = 0; i < numGroupsMax; i++) {
            if (mapping != null && mapping[i] == -1) {
                for (var j = 0; j < 5; j++) {
                    readHuffmanCode(ALPHABET_SIZE[j] + (j == 0 ? alphabetExtra : 0), null);
                }
                continue;
            }
            var group = new HTreeGroup();
            hdr.groups[mapping == null ? i : mapping[i]] = group;
            var isTrivialLiteral = true;
            var totalSize = 0;
            var maxBits = 0;
            for (var j = 0; j < 5; j++) {
                var alphabetSize = ALPHABET_SIZE[j] + (j == 0 ? alphabetExtra : 0);
                var start = hdr.used;
                readHuffmanCode(alphabetSize, hdr);
                group.trees[j] = start;
                switch (j) {
                    case GREEN -> group.greenTree = start;
                    case RED -> group.redTree = start;
                    case BLUE -> group.blueTree = start;
                    case ALPHA -> group.alphaTree = start;
                    default -> group.distTree = start;
                }
                var first = hdr.tables[start];
                if (isTrivialLiteral && LITERAL_MAP[j]) {
                    isTrivialLiteral = (first >>> 16) == 0;
                }
                totalSize += first >>> 16;
                if (j <= ALPHA) {
                    var localMax = codeLengths[0];
                    for (var k = 1; k < alphabetSize; k++) {
                        if (codeLengths[k] > localMax) {
                            localMax = codeLengths[k];
                        }
                    }
                    maxBits += localMax;
                }
            }
            group.isTrivialLiteral = isTrivialLiteral;
            if (isTrivialLiteral) {
                var red = hdr.tables[group.trees[RED]] & 0xFFFF;
                var blue = hdr.tables[group.trees[BLUE]] & 0xFFFF;
                var alpha = hdr.tables[group.trees[ALPHA]] & 0xFFFF;
                group.literalArb = (alpha << 24) | (red << 16) | blue;
                var green = hdr.tables[group.trees[GREEN]] & 0xFFFF;
                if (totalSize == 0 && green < NUM_LITERAL_CODES) {
                    group.isTrivialCode = true;
                    group.literalArb |= green << 8;
                }
            }
            group.usePackedTable = !group.isTrivialCode && maxBits < PACKED_BITS;
            if (group.usePackedTable) {
                buildPackedTable(group, hdr.tables);
            }
        }
        if (cacheBits > 0) {
            hdr.colorCache = new int[1 << cacheBits];
            hdr.colorCacheShift = 32 - cacheBits;
        }
        return hdr;
    }

    /**
     * Builds the 6-bit table that decodes green, red, blue and alpha of a literal in one lookup
     * when all four codes are short.
     *
     * @param group the Huffman codes of the group
     * @param tables the table storage
     */
    private static void buildPackedTable(HTreeGroup group, int[] tables) {
        var packedBits = new int[1 << PACKED_BITS];
        var packedValue = new int[1 << PACKED_BITS];
        for (var code = 0; code < (1 << PACKED_BITS); code++) {
            var bits = code;
            var hcode = tables[group.trees[GREEN] + bits];
            if ((hcode & 0xFFFF) >= NUM_LITERAL_CODES) {
                packedBits[code] = (hcode >>> 16) + BITS_SPECIAL_MARKER;
                packedValue[code] = hcode & 0xFFFF;
            } else {
                var total = 0;
                var value = 0;
                total += hcode >>> 16;
                value |= (hcode & 0xFFFF) << 8;
                bits >>>= hcode >>> 16;
                hcode = tables[group.trees[RED] + bits];
                total += hcode >>> 16;
                value |= (hcode & 0xFFFF) << 16;
                bits >>>= hcode >>> 16;
                hcode = tables[group.trees[BLUE] + bits];
                total += hcode >>> 16;
                value |= hcode & 0xFFFF;
                bits >>>= hcode >>> 16;
                hcode = tables[group.trees[ALPHA] + bits];
                total += hcode >>> 16;
                value |= (hcode & 0xFFFF) << 24;
                packedBits[code] = total;
                packedValue[code] = value;
            }
        }
        group.packedBits = packedBits;
        group.packedValue = packedValue;
    }

    /**
     * Reads one prefix code and, when a destination is given, appends its lookup table.
     *
     * @param alphabetSize the number of symbols
     * @param hdr the metadata receiving the table, or {@code null} to only validate
     */
    private void readHuffmanCode(int alphabetSize, Metadata hdr) {
        Arrays.fill(codeLengths, 0, alphabetSize, 0);
        if (br.readBit() == 1) {
            var numSymbols = br.readBit() + 1;
            var firstSymbolLenCode = br.readBit();
            var symbol = br.read(firstSymbolLenCode == 0 ? 1 : 8);
            codeLengths[symbol] = 1;
            if (numSymbols == 2) {
                symbol = br.read(8);
                codeLengths[symbol] = 1;
            }
        } else {
            var codeLengthCodeLengths = new int[NUM_CODE_LENGTH_CODES];
            var numCodes = br.read(4) + 4;
            for (var i = 0; i < numCodes; i++) {
                codeLengthCodeLengths[CODE_LENGTH_CODE_ORDER[i]] = br.read(3);
            }
            readHuffmanCodeLengths(codeLengthCodeLengths, alphabetSize);
        }
        checkNotOverrun();
        var size = HuffmanTables.build(null, 0, HUFFMAN_TABLE_BITS, codeLengths, alphabetSize, null);
        if (size == 0) {
            throw new MediaException.Malformed("invalid VP8L prefix code");
        }
        if (hdr != null) {
            hdr.ensureCapacity(size, limits);
            HuffmanTables.build(hdr.tables, hdr.used, HUFFMAN_TABLE_BITS, codeLengths, alphabetSize, sorted);
            hdr.used += size;
        }
    }

    /**
     * Reads code lengths coded with the code-length prefix code.
     *
     * @param codeLengthCodeLengths the lengths of the 19 code-length codes
     * @param numSymbols the alphabet size
     */
    private void readHuffmanCodeLengths(int[] codeLengthCodeLengths, int numSymbols) {
        var table = new int[1 << LENGTHS_TABLE_BITS];
        if (HuffmanTables.build(table, 0, LENGTHS_TABLE_BITS, codeLengthCodeLengths, NUM_CODE_LENGTH_CODES,
                new int[NUM_CODE_LENGTH_CODES]) == 0) {
            throw new MediaException.Malformed("invalid VP8L code-length code");
        }
        int maxSymbol;
        if (br.readBit() == 1) {
            var lengthBits = 2 + 2 * br.read(3);
            maxSymbol = 2 + br.read(lengthBits);
            if (maxSymbol > numSymbols) {
                throw new MediaException.Malformed("VP8L code length count " + maxSymbol + " exceeds " + numSymbols);
            }
        } else {
            maxSymbol = numSymbols;
        }
        var prevCodeLen = DEFAULT_CODE_LENGTH;
        var symbol = 0;
        while (symbol < numSymbols) {
            if (maxSymbol-- == 0) {
                break;
            }
            br.fill();
            var entry = table[br.prefetch() & ((1 << LENGTHS_TABLE_BITS) - 1)];
            br.consume(entry >>> 16);
            var codeLen = entry & 0xFFFF;
            if (codeLen < CODE_LENGTH_LITERALS) {
                codeLengths[symbol++] = codeLen;
                if (codeLen != 0) {
                    prevCodeLen = codeLen;
                }
            } else {
                var usePrev = codeLen == CODE_LENGTH_REPEAT_CODE;
                var slot = codeLen - CODE_LENGTH_LITERALS;
                var repeat = br.read(CODE_LENGTH_EXTRA_BITS[slot]) + CODE_LENGTH_REPEAT_OFFSETS[slot];
                if (symbol + repeat > numSymbols) {
                    throw new MediaException.Malformed("VP8L code length repeat overflows the alphabet");
                }
                var length = usePrev ? prevCodeLen : 0;
                while (repeat-- > 0) {
                    codeLengths[symbol++] = length;
                }
            }
            if (br.eos()) {
                throw new MediaException.Malformed("VP8L stream truncated in code lengths");
            }
        }
    }

    /**
     * Decodes the LZ77-coded pixels of an image.
     *
     * @param data the destination of {@code width * height} pixels
     * @param width the image width
     * @param height the image height
     * @param hdr the prefix codes and color cache
     * @param processRows whether rows are inverse-transformed and emitted while decoding (main
     * image only)
     */
    private void decodeImageData(int[] data, int width, int height, Metadata hdr, boolean processRows) {
        decodeImageData(data, width, height, hdr, processRows, height);
    }

    /**
     * Decodes the LZ77-coded pixels of the top rows of an image; a backward reference that reaches below them is
     * checked against the whole image and copied as far as they reach.
     *
     * @param data the destination of {@code width * rows} pixels
     * @param width the image width
     * @param height the image height
     * @param hdr the prefix codes and color cache
     * @param processRows whether rows are inverse-transformed and emitted while decoding (main
     * image only)
     * @param rows the number of rows to decode, from 1 to {@code height}
     */
    private void decodeImageData(int[] data, int width, int height, Metadata hdr, boolean processRows, int rows) {
        var br = this.br;
        var tables = hdr.tables;
        var whole = (long) width * height;
        var end = width * rows;
        var pos = 0;
        var col = 0;
        var row = 0;
        var lastCached = 0;
        var mask = hdr.huffmanMask;
        var colorCache = hdr.colorCache;
        var cacheShift = hdr.colorCacheShift;
        var cacheMask = colorCache == null ? 0 : colorCache.length - 1;
        var lenCodeLimit = NUM_LITERAL_CODES + NUM_LENGTH_CODES;
        var colorCacheLimit = lenCodeLimit + (colorCache == null ? 0 : colorCache.length);
        var group = end > 0 ? groupAt(hdr, 0, 0) : null;
        while (pos < end) {
            if ((col & mask) == 0) {
                group = groupAt(hdr, col, row);
            }
            int code;
            if (group.isTrivialCode) {
                data[pos] = group.literalArb;
                code = -1;
            } else {
                br.fill();
                if (group.usePackedTable) {
                    var index = br.prefetch() & ((1 << PACKED_BITS) - 1);
                    var bits = group.packedBits[index];
                    if (bits < BITS_SPECIAL_MARKER) {
                        br.consume(bits);
                        data[pos] = group.packedValue[index];
                        code = -1;
                    } else {
                        br.consume(bits - BITS_SPECIAL_MARKER);
                        code = group.packedValue[index];
                    }
                } else {
                    code = readSymbol(tables, group.greenTree, br);
                }
                if (code >= 0 && code < NUM_LITERAL_CODES) {
                    if (group.isTrivialLiteral) {
                        data[pos] = group.literalArb | (code << 8);
                    } else {
                        var red = readSymbol(tables, group.redTree, br);
                        br.fill();
                        var blue = readSymbol(tables, group.blueTree, br);
                        var alpha = readSymbol(tables, group.alphaTree, br);
                        data[pos] = (alpha << 24) | (red << 16) | (code << 8) | blue;
                    }
                    code = -1;
                }
            }
            if (code < 0) {
                ++pos;
                ++col;
                if (col >= width) {
                    col = 0;
                    ++row;
                    if (br.eos()) {
                        throw new MediaException.Malformed("VP8L stream truncated in pixel data");
                    }
                    if (processRows && (row & (NUM_ARGB_CACHE_ROWS - 1)) == 0) {
                        processRows(row);
                    }
                    if (colorCache != null) {
                        while (lastCached < pos) {
                            var argb = data[lastCached++];
                            colorCache[((argb * HASH_MUL) >>> cacheShift) & cacheMask] = argb;
                        }
                    }
                }
            } else if (code < lenCodeLimit) {
                var length = copyDistance(code - NUM_LITERAL_CODES, br);
                br.fill();
                var distSymbol = readSymbol(tables, group.distTree, br);
                br.fill();
                var distCode = copyDistance(distSymbol, br);
                var dist = planeCodeToDistance(width, distCode);
                if (br.eos()) {
                    throw new MediaException.Malformed("VP8L stream truncated in pixel data");
                }
                if (pos < dist || whole - pos < length) {
                    throw new MediaException.Malformed("VP8L backward reference out of range");
                }
                if (end - pos < length) {
                    // the copy reaches below the rows decoded: copy as far as they reach and stop there
                    copyBlock(data, pos, dist, end - pos);
                    col += end - pos;
                    pos = end;
                    while (col >= width) {
                        col -= width;
                        ++row;
                        if (processRows && (row & (NUM_ARGB_CACHE_ROWS - 1)) == 0) {
                            processRows(row);
                        }
                    }
                    break;
                }
                copyBlock(data, pos, dist, length);
                pos += length;
                col += length;
                while (col >= width) {
                    col -= width;
                    ++row;
                    if (processRows && (row & (NUM_ARGB_CACHE_ROWS - 1)) == 0) {
                        processRows(row);
                    }
                }
                if ((col & mask) != 0 && pos < end) {
                    group = groupAt(hdr, col, row);
                }
                if (colorCache != null) {
                    while (lastCached < pos) {
                        var argb = data[lastCached++];
                        colorCache[((argb * HASH_MUL) >>> cacheShift) & cacheMask] = argb;
                    }
                }
            } else if (code < colorCacheLimit) {
                var key = code - lenCodeLimit;
                while (lastCached < pos) {
                    var argb = data[lastCached++];
                    colorCache[((argb * HASH_MUL) >>> cacheShift) & cacheMask] = argb;
                }
                data[pos] = colorCache[key];
                ++pos;
                ++col;
                if (col >= width) {
                    col = 0;
                    ++row;
                    if (br.eos()) {
                        throw new MediaException.Malformed("VP8L stream truncated in pixel data");
                    }
                    if (processRows && (row & (NUM_ARGB_CACHE_ROWS - 1)) == 0) {
                        processRows(row);
                    }
                    while (lastCached < pos) {
                        var argb = data[lastCached++];
                        colorCache[((argb * HASH_MUL) >>> cacheShift) & cacheMask] = argb;
                    }
                }
            } else {
                throw new MediaException.Malformed("VP8L symbol " + code + " out of range");
            }
        }
        if (rows == height) {
            checkNotOverrun();
        }
        if (processRows) {
            processRows(Math.min(row, rows));
        }
    }

    /**
     * Returns the prefix code group of a position.
     *
     * @param hdr the metadata
     * @param x the column
     * @param y the row
     * @return the group
     */
    private static HTreeGroup groupAt(Metadata hdr, int x, int y) {
        if (hdr.huffmanBits == 0) {
            return hdr.groups[0];
        }
        var bits = hdr.huffmanBits;
        return hdr.groups[hdr.huffmanImage[hdr.huffmanXSize * (y >> bits) + (x >> bits)]];
    }

    /**
     * Decodes one symbol with a two-level table; the caller has ensured 15 cached bits.
     *
     * @param tables the table storage
     * @param offset the index of the root table
     * @param br the bit reader
     * @return the symbol
     */
    private static int readSymbol(int[] tables, int offset, Vp8lBitReader br) {
        var val = br.prefetch();
        var index = offset + (val & ((1 << HUFFMAN_TABLE_BITS) - 1));
        var entry = tables[index];
        var nbits = (entry >>> 16) - HUFFMAN_TABLE_BITS;
        if (nbits > 0) {
            br.consume(HUFFMAN_TABLE_BITS);
            index += (entry & 0xFFFF) + ((val >>> HUFFMAN_TABLE_BITS) & ((1 << nbits) - 1));
            entry = tables[index];
        }
        br.consume(entry >>> 16);
        return entry & 0xFFFF;
    }

    /**
     * Decodes a length or distance from its prefix symbol and extra bits.
     *
     * @param symbol the prefix symbol
     * @param br the bit reader
     * @return the length or distance code, at least 1
     */
    private static int copyDistance(int symbol, Vp8lBitReader br) {
        if (symbol < 4) {
            return symbol + 1;
        }
        var extraBits = (symbol - 2) >> 1;
        var offset = (2 + (symbol & 1)) << extraBits;
        return offset + br.read(extraBits) + 1;
    }

    /**
     * Maps a distance code to a linear distance through the 2D distance map.
     *
     * @param xsize the image width
     * @param planeCode the distance code
     * @return the distance in pixels, at least 1
     */
    static int planeCodeToDistance(int xsize, int planeCode) {
        if (planeCode > CODE_TO_PLANE.length) {
            return planeCode - CODE_TO_PLANE.length;
        }
        var distCode = CODE_TO_PLANE[planeCode - 1];
        var yoffset = distCode >> 4;
        var xoffset = 8 - (distCode & 0xF);
        var dist = yoffset * xsize + xoffset;
        return dist >= 1 ? dist : 1;
    }

    /**
     * Copies a possibly overlapping run of pixels from {@code dist} positions back.
     *
     * @param data the pixels
     * @param pos the index of the first pixel to write
     * @param dist the backward distance
     * @param length the number of pixels
     */
    private static void copyBlock(int[] data, int pos, int dist, int length) {
        if (length >= SHORT_COPY) {
            if (dist >= length) {
                System.arraycopy(data, pos - dist, data, pos, length);
                return;
            }
            if (dist == 1) {
                Arrays.fill(data, pos, pos + length, data[pos - 1]);
                return;
            }
        }
        var src = pos - dist;
        for (var i = 0; i < length; i++) {
            data[pos + i] = data[src + i];
        }
    }

    /**
     * Inverse-transforms and emits the rows decoded since the last call.
     *
     * @param row the row after the last complete decoded row
     */
    private void processRows(int row) {
        var numRows = row - lastRow;
        if (numRows <= 0) {
            return;
        }
        applyInverseTransforms(lastRow, numRows);
        if (rgbaOut != null) {
            for (var y = 0; y < numRows; y++) {
                DSP.argbToRgba(argbCache, cacheStart + y * outWidth, rgbaOut, rgbaOffset + (lastRow + y) * rgbaStride, outWidth);
            }
        } else {
            var base = lastRow * outWidth;
            for (var i = 0; i < numRows * outWidth; i++) {
                alphaOut[base + i] = (byte) (argbCache[cacheStart + i] >>> 8);
            }
            for (var y = 0; y < numRows; y++) {
                var line = base + y * outWidth;
                AlphaFilters.unfilter(alphaFilter, alphaPrevLine < 0 ? null : alphaOut, alphaPrevLine,
                        alphaOut, line, alphaOut, line, outWidth);
                alphaPrevLine = line;
            }
        }
        lastRow = row;
    }

    /**
     * Applies the inverse transforms, last read first, to rows of the entropy-coded image,
     * leaving the result in {@link #argbCache}.
     *
     * @param startRow the first row
     * @param numRows the number of rows
     */
    private void applyInverseTransforms(int startRow, int numRows) {
        int[] in = pixels;
        var inPos = startRow * codedWidth;
        var out = argbCache;
        var n = numTransforms;
        while (n-- > 0) {
            inverseTransform(transforms[n], startRow, startRow + numRows, in, inPos, out, cacheStart);
            in = out;
            inPos = cacheStart;
        }
        if (in != out) {
            System.arraycopy(pixels, startRow * codedWidth, out, cacheStart, codedWidth * numRows);
        }
    }

    /**
     * Applies one inverse transform to a batch of rows.
     *
     * @param transform the transform to invert
     * @param rowStart the first row
     * @param rowEnd the row after the last
     * @param in the source pixels
     * @param inPos the index of the first source pixel
     * @param out the destination pixels
     * @param outPos the index of the first destination pixel
     */
    private void inverseTransform(Transform transform, int rowStart, int rowEnd, int[] in, int inPos, int[] out, int outPos) {
        var width = transform.xsize;
        switch (transform.type) {
            case SUBTRACT_GREEN_TRANSFORM -> DSP.addGreenToBlueAndRed(in, inPos, out, outPos, (rowEnd - rowStart) * width);
            case PREDICTOR_TRANSFORM -> {
                predictorInverse(transform, rowStart, rowEnd, in, inPos, out, outPos);
                if (rowEnd != transform.ysize) {
                    System.arraycopy(out, outPos + (rowEnd - rowStart - 1) * width, out, outPos - width, width);
                }
            }
            case CROSS_COLOR_TRANSFORM -> colorSpaceInverse(transform, rowStart, rowEnd, in, inPos, out, outPos);
            default -> {
                if (in == out && transform.bits > 0) {
                    var outStride = (rowEnd - rowStart) * width;
                    var inStride = (rowEnd - rowStart) * subSampleSize(width, transform.bits);
                    var src = outPos + outStride - inStride;
                    System.arraycopy(out, outPos, out, src, inStride);
                    colorIndexInverse(transform, rowStart, rowEnd, out, src, out, outPos);
                } else {
                    colorIndexInverse(transform, rowStart, rowEnd, in, inPos, out, outPos);
                }
            }
        }
    }

    /**
     * Applies the inverse predictor transform.
     *
     * @param transform the predictor transform
     * @param yStart the first row
     * @param yEnd the row after the last
     * @param in the residuals
     * @param inPos the index of the first residual
     * @param out the destination
     * @param outPos the index of the first destination pixel
     */
    private static void predictorInverse(Transform transform, int yStart, int yEnd, int[] in, int inPos, int[] out, int outPos) {
        var width = transform.xsize;
        if (yStart == 0) {
            DSP.predictorAdd(0, in, inPos, out, outPos, 1, width);
            DSP.predictorAdd(1, in, inPos + 1, out, outPos + 1, width - 1, width);
            inPos += width;
            outPos += width;
            ++yStart;
        }
        var y = yStart;
        var tileWidth = 1 << transform.bits;
        var mask = tileWidth - 1;
        var tilesPerRow = subSampleSize(width, transform.bits);
        var modes = transform.data;
        var modeBase = (y >> transform.bits) * tilesPerRow;
        while (y < yEnd) {
            var modeSrc = modeBase;
            DSP.predictorAdd(2, in, inPos, out, outPos, 1, width);
            var x = 1;
            while (x < width) {
                var mode = (modes[modeSrc++] >>> 8) & 0xF;
                var xEnd = (x & ~mask) + tileWidth;
                if (xEnd > width) {
                    xEnd = width;
                }
                DSP.predictorAdd(mode, in, inPos + x, out, outPos + x, xEnd - x, width);
                x = xEnd;
            }
            inPos += width;
            outPos += width;
            ++y;
            if ((y & mask) == 0) {
                modeBase += tilesPerRow;
            }
        }
    }

    /**
     * Applies the inverse cross-color transform.
     *
     * @param transform the cross-color transform
     * @param yStart the first row
     * @param yEnd the row after the last
     * @param src the source pixels
     * @param srcPos the index of the first source pixel
     * @param dst the destination
     * @param dstPos the index of the first destination pixel
     */
    private static void colorSpaceInverse(Transform transform, int yStart, int yEnd, int[] src, int srcPos, int[] dst, int dstPos) {
        var width = transform.xsize;
        var tileWidth = 1 << transform.bits;
        var mask = tileWidth - 1;
        var tilesPerRow = subSampleSize(width, transform.bits);
        var y = yStart;
        var predRow = (y >> transform.bits) * tilesPerRow;
        var codes = transform.data;
        while (y < yEnd) {
            var pred = predRow;
            for (var x = 0; x < width; x += tileWidth) {
                var count = Math.min(tileWidth, width - x);
                DSP.transformColorInverse(codes[pred++], src, srcPos + x, dst, dstPos + x, count);
            }
            srcPos += width;
            dstPos += width;
            ++y;
            if ((y & mask) == 0) {
                predRow += tilesPerRow;
            }
        }
    }

    /**
     * Applies the inverse color indexing transform, unbundling packed indices.
     *
     * @param transform the color indexing transform
     * @param yStart the first row
     * @param yEnd the row after the last
     * @param src the packed indices (in the green channel)
     * @param srcPos the index of the first packed pixel
     * @param dst the destination
     * @param dstPos the index of the first destination pixel
     */
    private static void colorIndexInverse(Transform transform, int yStart, int yEnd, int[] src, int srcPos, int[] dst, int dstPos) {
        var bitsPerPixel = 8 >> transform.bits;
        var width = transform.xsize;
        var colorMap = transform.data;
        if (bitsPerPixel < 8) {
            var countMask = (1 << transform.bits) - 1;
            var bitMask = (1 << bitsPerPixel) - 1;
            for (var y = yStart; y < yEnd; y++) {
                var packed = 0;
                for (var x = 0; x < width; x++) {
                    if ((x & countMask) == 0) {
                        packed = (src[srcPos++] >>> 8) & 0xFF;
                    }
                    dst[dstPos++] = colorMap[packed & bitMask];
                    packed >>>= bitsPerPixel;
                }
            }
        } else {
            var count = (yEnd - yStart) * width;
            for (var i = 0; i < count; i++) {
                dst[dstPos + i] = colorMap[(src[srcPos + i] >>> 8) & 0xFF];
            }
        }
    }

    /**
     * Rejects the stream if a read ran past its end.
     */
    private void checkNotOverrun() {
        if (br.eos()) {
            throw new MediaException.Malformed("VP8L stream truncated");
        }
    }

    /**
     * Returns {@code ceil(size / 2^bits)}.
     *
     * @param size the full size
     * @param bits the subsampling width in bits
     * @return the subsampled size
     */
    public static int subSampleSize(int size, int bits) {
        return (size + (1 << bits) - 1) >> bits;
    }

    /**
     * Holds a decoded lossless image.
     *
     * @param width the width in pixels
     * @param height the height in pixels
     * @param alphaHint the alpha hint bit of the header
     * @param rgba the packed RGBA samples
     */
    public record Result(int width, int height, boolean alphaHint, byte[] rgba) {
    }

    /**
     * Holds one transform read from the stream.
     */
    private static final class Transform {
        /**
         * The transform type.
         */
        final int type;

        /**
         * The image width the transform applies to.
         */
        final int xsize;

        /**
         * The image height.
         */
        final int ysize;

        /**
         * The tile width in bits, or the bundling width for color indexing.
         */
        int bits;

        /**
         * The transform data: predictor modes, color multipliers or the color map.
         */
        int[] data;

        /**
         * Constructs a transform record.
         *
         * @param type the transform type
         * @param xsize the image width the transform applies to
         * @param ysize the image height
         */
        Transform(int type, int xsize, int ysize) {
            this.type = type;
            this.xsize = xsize;
            this.ysize = ysize;
        }
    }

    /**
     * Holds the five prefix codes of one meta code.
     */
    private static final class HTreeGroup {
        /**
         * The index of the root table of each code in the table storage.
         */
        final int[] trees = new int[5];

        /**
         * The index of the green root table.
         */
        int greenTree;

        /**
         * The index of the red root table.
         */
        int redTree;

        /**
         * The index of the blue root table.
         */
        int blueTree;

        /**
         * The index of the alpha root table.
         */
        int alphaTree;

        /**
         * The index of the distance root table.
         */
        int distTree;

        /**
         * Whether red, blue and alpha each have a single symbol.
         */
        boolean isTrivialLiteral;

        /**
         * Whether every literal is the same pixel, {@link #literalArb}.
         */
        boolean isTrivialCode;

        /**
         * The constant red, blue and alpha (and green when {@link #isTrivialCode}) of literals.
         */
        int literalArb;

        /**
         * Whether literals are decoded through the packed 6-bit table.
         */
        boolean usePackedTable;

        /**
         * The bit counts of the packed table.
         */
        int[] packedBits;

        /**
         * The values of the packed table: an ARGB literal or a green symbol.
         */
        int[] packedValue;
    }

    /**
     * Holds the decoding state of one image level.
     */
    private static final class Metadata {
        /**
         * The entropy image width in bits, or {@code 0} without meta codes.
         */
        int huffmanBits;

        /**
         * The width of the entropy image.
         */
        int huffmanXSize;

        /**
         * The mask selecting the column within an entropy tile.
         */
        int huffmanMask;

        /**
         * The group index of each entropy tile, or {@code null}.
         */
        int[] huffmanImage;

        /**
         * The prefix code groups.
         */
        HTreeGroup[] groups;

        /**
         * The lookup table storage shared by every group.
         */
        int[] tables;

        /**
         * The number of used entries of {@link #tables}.
         */
        int used;

        /**
         * The color cache, or {@code null}.
         */
        int[] colorCache;

        /**
         * The right shift of the color cache hash.
         */
        int colorCacheShift;

        /**
         * Grows the table storage to hold {@code more} additional entries.
         *
         * @param more the number of entries about to be appended
         * @param limits the resource bounds
         */
        void ensureCapacity(int more, MediaLimits limits) {
            var needed = (long) used + more;
            if (needed <= tables.length) {
                return;
            }
            var grown = Math.max(needed, (long) tables.length * 2);
            LimitChecks.checkAllocation(limits, grown * 4);
            tables = Arrays.copyOf(tables, (int) grown);
        }
    }
}
