package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.PixelDensity;
import com.github.auties00.smalt.image.ops.Region;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.internal.tiff.TiffExif;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Parses a JPEG codestream held in memory and decodes its scans.
 *
 * <p>The reader walks the markers from SOI: it records quantization, Huffman and arithmetic
 * conditioning tables, the restart interval, the frame header, and the metadata applications
 * write ({@code APP0 JFIF}, the first {@code APP1 Exif}, {@code APP2 ICC_PROFILE} chunks,
 * {@code APP14 Adobe}), and decodes every scan with the tables in force at that point. Garbage
 * between segments is skipped up to the next marker, fill bytes are allowed, data after EOI is
 * ignored, and a missing EOI ends the image at the end of the input. A frame whose height is zero
 * gets it from the DNL segment that follows its first scan. Huffman table slots 0 and 1 start out
 * holding the example tables of T.81 Annex K, so streams that omit their DHT segments, as
 * Motion-JPEG frames do, decode. The scans of a lossless (SOF3, SOF11) frame are decoded by
 * {@link LosslessOutput} through {@link #openLossless()}, not by this reader's scan decoders.
 *
 * <p>Entropy-coded data never makes the decode fail: corrupt or truncated segments turn into gray
 * blocks and decoding resumes at the next restart marker. An input cut off inside a header segment
 * after the first scan ends the image there, keeping what the earlier scans decoded. Only an image
 * from which not a single MCU could be decoded is rejected as {@link MediaException.Malformed}.
 *
 * <p>An instance decodes one input once and is not thread-safe.
 *
 * @implNote This implementation follows libjpeg-turbo's marker reader (jdmarker.c) for segment
 * syntax, marker resynchronization and color-space inference (jdapimin.c), and its input controller
 * (jdinput.c) for latching each component's quantization table at the component's first scan.
 * Unlike libjpeg, it accepts a frame height defined by DNL.
 */
public final class JpegReader {
    /**
     * The largest number of blocks in one MCU of an interleaved scan, T.81 B.2.3.
     */
    private static final int MAX_BLOCKS_IN_MCU = 10;

    /**
     * The approximate number of operations to dequantize and transform one block, which weighs a
     * component's work against {@link ParallelBands#MIN_PARALLEL_WORK}.
     */
    private static final long BLOCK_WORK = 128;

    /**
     * The input.
     */
    private final byte[] input;

    /**
     * The index of the SOI marker.
     */
    private final int start;

    /**
     * The index one past the last input byte.
     */
    private final int end;

    /**
     * The resource bounds.
     */
    private final MediaLimits limits;

    /**
     * The quantization tables by slot, in zigzag order, or {@code null} when undefined.
     */
    private final int[][] quantTables = new int[4][];

    /**
     * The DC Huffman tables by slot.
     */
    private final HuffmanSpec[] dcSpecs = new HuffmanSpec[4];

    /**
     * The AC Huffman tables by slot.
     */
    private final HuffmanSpec[] acSpecs = new HuffmanSpec[4];

    /**
     * The decoding tables built from {@link #dcSpecs}, invalidated when a slot is redefined.
     */
    private final HuffmanDecodingTable[] dcDecoding = new HuffmanDecodingTable[4];

    /**
     * The decoding tables built from {@link #acSpecs}, invalidated when a slot is redefined.
     */
    private final HuffmanDecodingTable[] acDecoding = new HuffmanDecodingTable[4];

    /**
     * The lower DC conditioning bound of each arithmetic table slot.
     */
    private final int[] arithmeticDcLower = new int[16];

    /**
     * The upper DC conditioning bound of each arithmetic table slot.
     */
    private final int[] arithmeticDcUpper = new int[16];

    /**
     * The AC conditioning threshold of each arithmetic table slot.
     */
    private final int[] arithmeticAcThreshold = new int[16];

    /**
     * The chunks of the ICC profile.
     */
    private final JpegIcc icc = new JpegIcc();

    /**
     * The entropy-coded data of the current scan.
     */
    private final EntropySegments segments;

    /**
     * The parse position.
     */
    private int position;

    /**
     * The restart interval in MCUs, or zero.
     */
    private int restartInterval;

    /**
     * The frame, or {@code null} before the SOF segment.
     */
    private JpegFrame frame;

    /**
     * Whether a JFIF APP0 segment was seen before the first scan.
     */
    private boolean jfif;

    /**
     * The density of the first JFIF APP0 segment, or {@code null} when it is absent or invalid.
     */
    private PixelDensity jfifDensity;

    /**
     * The transform flag of the last Adobe APP14 segment seen before the first scan, or
     * {@code -1}.
     */
    private int adobeTransform = -1;

    /**
     * The TIFF block of the first Exif APP1 segment, or {@code null}.
     */
    private byte[] exif;

    /**
     * The packet of the first XMP APP1 segment, or {@code null}.
     */
    private byte[] xmp;

    /**
     * The payloads of the Extended XMP APP1 segments, identifier included.
     */
    private final List<byte[]> extendedXmp = new ArrayList<>();

    /**
     * The index just after the first SOS marker code, or {@code -1} before the headers are read.
     */
    private int firstScan = -1;

    /**
     * Whether at least one scan was decoded, after which a truncated segment ends the image
     * instead of failing it.
     */
    private boolean scansDecoded;

    /**
     * The Huffman scan decoder, created on first use.
     */
    private HuffmanScanDecoder huffman;

    /**
     * The arithmetic scan decoder, created on first use.
     */
    private ArithmeticScanDecoder arithmetic;

    /**
     * The decoder of a JPEG-LS frame, created with the headers, or {@code null}.
     */
    private JpegLsDecoder jpegLs;

    /**
     * The color space of the first {@code APP8} SPIFF header before the first scan, or {@code -1}.
     */
    private int spiffColorSpace = -1;

    /**
     * The frame header of the completed image that the DHP segment of a hierarchical image defines, or
     * {@code null} for any other image.
     */
    private JpegFrame image;

    /**
     * Whether the scans of the current frame are being read, so that the next frame header or EXP segment of a
     * hierarchical image ends them.
     */
    private boolean frameOpen;

    /**
     * The index of the {@code 0xFF} of the last marker read.
     */
    private int markerAt;

    /**
     * The index of the {@code 0xFF} of the SOF marker of the current frame.
     */
    private int frameStart;

    /**
     * The expansion the EXP segment before the current frame asks for, horizontal in bit 1 and vertical in bit 0,
     * or {@code -1} when no EXP segment precedes it.
     */
    private int expansion = -1;

    /**
     * Whether a DRI segment before the frame header has the 3 or 4-byte restart interval only JPEG-LS allows.
     */
    private boolean wideRestartInterval;

    /**
     * The decoded components of a hierarchical image, or {@code null} before it is decoded.
     */
    private HierarchicalImage hierarchy;

    /**
     * Constructs a reader over {@code input[offset, offset + length)}.
     *
     * @param input the array holding the codestream
     * @param offset the index of the SOI marker
     * @param length the number of bytes of the codestream
     * @param limits the resource bounds
     * @throws NullPointerException if {@code input} or {@code limits} is {@code null}
     * @throws IndexOutOfBoundsException if the range is invalid
     */
    public JpegReader(byte[] input, int offset, int length, MediaLimits limits) {
        this.input = Objects.requireNonNull(input, "input");
        Objects.checkFromIndexSize(offset, length, input.length);
        this.limits = Objects.requireNonNull(limits, "limits");
        this.start = offset;
        this.end = offset + length;
        this.segments = new EntropySegments(limits);
        dcSpecs[0] = HuffmanSpec.STD_DC_LUMINANCE;
        dcSpecs[1] = HuffmanSpec.STD_DC_CHROMINANCE;
        acSpecs[0] = HuffmanSpec.STD_AC_LUMINANCE;
        acSpecs[1] = HuffmanSpec.STD_AC_CHROMINANCE;
        Arrays.fill(arithmeticDcUpper, 1);
        Arrays.fill(arithmeticAcThreshold, 5);
    }

    /**
     * Parses every segment up to the first scan.
     *
     * <p>When the frame height is zero, the first scan's entropy data is located to read the DNL
     * segment after it. Calling this method again returns the frame without parsing.
     *
     * @return the frame
     * @throws MediaException.Malformed if the input does not start with SOI, a segment is truncated
     * or invalid, or the input has no frame or no scan
     * @throws MediaException.Unsupported if the input uses JPEG-LS part 2 extensions or the JPG extension
     * marker
     */
    public JpegFrame readHeaders() {
        if (firstScan >= 0) {
            return frame();
        }
        if (end - start < 2 || (input[start] & 0xFF) != 0xFF || (input[start + 1] & 0xFF) != 0xD8) {
            throw new MediaException.Malformed("missing JPEG SOI marker");
        }
        position = start + 2;
        var sawScan = parseUntilScan();
        if (frame == null) {
            throw new MediaException.Malformed("JPEG stream has no frame header");
        }
        if (!sawScan) {
            throw new MediaException.Malformed("JPEG stream has no scan");
        }
        firstScan = position;
        frameOpen = true;
        if (frame.marker == 0xF7) {
            openJpegLs();
        } else if (frame.height == 0) {
            resolveDnl();
        }
        return frame();
    }

    /**
     * Parses the whole marker structure of a JPEG-LS stream and replaces the frame with the frame of its
     * decoded samples: its size from the oversize dimension or {@code DNL} segment when the frame header leaves it
     * at zero, the precision of the mapped samples, and three components for a palette image.
     */
    private void openJpegLs() {
        jpegLs = new JpegLsDecoder(input, start, end - start, limits);
        var count = jpegLs.outputComponents();
        var components = new JpegComponent[count];
        for (var i = 0; i < count; i++) {
            var sampling = jpegLs.sampling(i);
            components[i] = new JpegComponent(jpegLs.id(i), sampling[0], sampling[1], 0, i);
        }
        frame = new JpegFrame(0xF7, jpegLs.outputPrecision(), jpegLs.width(), jpegLs.height(), components);
    }

    /**
     * Reads the DNL segment after the first scan to define the frame height.
     *
     * @throws MediaException.Malformed if the first scan is not followed by a valid DNL segment
     */
    private void resolveDnl() {
        var header = segmentLength(position);
        segments.load(input, position + header, end);
        var at = segments.scanEnd();
        if (at + 6 > end || (input[at + 1] & 0xFF) != 0xDC) {
            throw new MediaException.Malformed("JPEG frame height is zero and no DNL segment defines it");
        }
        var length = u16(at + 2);
        var lines = u16(at + 4);
        if (length != 4 || lines == 0) {
            throw new MediaException.Malformed("invalid DNL segment");
        }
        frame.setHeight(lines);
    }

    /**
     * Parses segments until an SOS marker or the end of the image.
     *
     * @return {@code true} when positioned just after an SOS marker code, {@code false} at EOI or
     * the end of the input
     */
    private boolean parseUntilScan() {
        while (true) {
            var marker = nextMarker();
            if (scansDecoded && hasLength(marker) && truncated()) {
                return false;
            }
            switch (marker) {
                case -1, 0xD9 -> {
                    return false;
                }
                case 0xDA -> {
                    if (frame == null) {
                        throw new MediaException.Malformed("JPEG scan before the frame header");
                    }
                    return true;
                }
                case 0xC4 -> parseDht();
                case 0xDB -> parseDqt();
                case 0xDD -> parseDri();
                case 0xCC -> parseDac();
                case 0xDC -> parseDnl();
                case 0xD8 -> throw new MediaException.Malformed("duplicate JPEG SOI marker");
                case 0xC8 -> throw new MediaException.Unsupported("jpeg extensions", "JPEG stream uses the reserved JPG extension marker");
                case 0xDE -> parseDhp();
                case 0xDF -> {
                    if (image == null) {
                        throw new MediaException.Malformed("JPEG EXP segment outside hierarchical coding");
                    }
                    if (frameOpen) {
                        position = markerAt;
                        return false;
                    }
                    parseExp();
                }
                case 0xF8 -> skipSegment();
                case 0xF9 -> throw new MediaException.Unsupported("jpeg-ls extensions",
                        "JPEG-LS part 2 (ITU-T T.870) streams are not supported");
                case 0xFE -> skipSegment();
                case 0x01, 0xD0, 0xD1, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7 -> {
                }
                default -> {
                    if ((marker >= 0xC0 && marker <= 0xCF) || marker == 0xF7) {
                        if (image != null && frameOpen) {
                            position = markerAt;
                            return false;
                        }
                        parseSof(marker);
                    } else if (marker >= 0xE0 && marker <= 0xEF) {
                        parseApp(marker);
                    } else if (marker >= 0xF0 && marker <= 0xFD) {
                        skipSegment();
                    } else {
                        throw new MediaException.Malformed("unknown JPEG marker 0x" + Integer.toHexString(marker));
                    }
                }
            }
        }
    }

    /**
     * Returns whether a marker code starts a segment with a length field.
     *
     * @param marker the marker code, or {@code -1}
     * @return {@code false} for SOI, EOI, TEM, the restart markers and the end of the input
     */
    private static boolean hasLength(int marker) {
        return marker > 0x01 && marker != 0xD8 && marker != 0xD9 && (marker < 0xD0 || marker > 0xD7);
    }

    /**
     * Returns whether the segment at {@link #position} runs past the end of the input.
     *
     * @return {@code true} when its length field or its payload is cut off
     */
    private boolean truncated() {
        return position + 2 > end || position + u16(position) > end;
    }

    /**
     * Finds the next marker, skipping bytes that are not part of one.
     *
     * @return the marker code, or {@code -1} at the end of the input
     */
    private int nextMarker() {
        while (position < end) {
            if (input[position] != (byte) 0xFF) {
                position++;
                continue;
            }
            var p = position + 1;
            while (p < end && input[p] == (byte) 0xFF) {
                p++;
            }
            if (p >= end) {
                position = end;
                return -1;
            }
            var code = input[p] & 0xFF;
            position = p + 1;
            if (code != 0) {
                markerAt = p - 1;
                return code;
            }
        }
        return -1;
    }

    /**
     * Reads the length field of the segment at {@link #position} and checks it.
     *
     * @param at the index of the length field
     * @return the segment length, including the two length bytes
     * @throws MediaException.Malformed if the length is below 2 or runs past the input
     */
    private int segmentLength(int at) {
        if (at + 2 > end) {
            throw new MediaException.Malformed("JPEG segment truncated at offset " + (at - start));
        }
        var length = u16(at);
        if (length < 2 || at + length > end) {
            throw new MediaException.Malformed("JPEG segment of length " + length + " at offset " + (at - start)
                    + " runs past the end of the input");
        }
        return length;
    }

    /**
     * Skips the segment at {@link #position}.
     */
    private void skipSegment() {
        position += segmentLength(position);
    }

    /**
     * Parses a DQT segment.
     */
    private void parseDqt() {
        var length = segmentLength(position);
        var at = position + 2;
        var limit = position + length;
        while (at < limit) {
            var pq = (input[at] & 0xFF) >>> 4;
            var tq = input[at] & 15;
            at++;
            if (pq > 1 || tq > 3) {
                throw new MediaException.Malformed("invalid DQT table specification 0x" + Integer.toHexString(input[at - 1] & 0xFF));
            }
            var bytes = pq == 0 ? 64 : 128;
            if (at + bytes > limit) {
                throw new MediaException.Malformed("truncated DQT segment");
            }
            var table = new int[64];
            for (var k = 0; k < 64; k++) {
                table[k] = pq == 0 ? input[at + k] & 0xFF : u16(at + 2 * k);
            }
            quantTables[tq] = table;
            at += bytes;
        }
        position = limit;
    }

    /**
     * Parses a DHT segment.
     */
    private void parseDht() {
        var length = segmentLength(position);
        var at = position + 2;
        var limit = position + length;
        while (at < limit) {
            if (at + 17 > limit) {
                throw new MediaException.Malformed("truncated DHT segment");
            }
            var index = input[at] & 0xFF;
            if ((index & 0xEC) != 0) {
                throw new MediaException.Malformed("invalid DHT table index 0x" + Integer.toHexString(index));
            }
            var bits = new int[17];
            var total = 0;
            for (var l = 1; l <= 16; l++) {
                bits[l] = input[at + l] & 0xFF;
                total += bits[l];
            }
            at += 17;
            if (total > 256 || at + total > limit) {
                throw new MediaException.Malformed("invalid DHT symbol count " + total);
            }
            var values = new int[total];
            for (var i = 0; i < total; i++) {
                values[i] = input[at + i] & 0xFF;
            }
            at += total;
            var spec = new HuffmanSpec(bits, values);
            var slot = index & 3;
            if ((index & 0x10) == 0) {
                dcSpecs[slot] = spec;
                dcDecoding[slot] = null;
            } else {
                acSpecs[slot] = spec;
                acDecoding[slot] = null;
            }
        }
        position = limit;
    }

    /**
     * Parses a DRI segment.
     */
    private void parseDri() {
        var length = segmentLength(position);
        var jpegLsFrame = frame == null || frame.marker == 0xF7;
        if (length != 4 && !(jpegLsFrame && (length == 5 || length == 6))) {
            throw new MediaException.Malformed("invalid DRI segment length " + length);
        }
        if (length == 4) {
            restartInterval = u16(position + 2);
        } else if (frame == null) {
            wideRestartInterval = true;
        }
        position += length;
    }

    /**
     * Parses a DAC segment.
     */
    private void parseDac() {
        var length = segmentLength(position);
        var at = position + 2;
        var limit = position + length;
        if ((length - 2) % 2 != 0) {
            throw new MediaException.Malformed("invalid DAC segment length " + length);
        }
        while (at < limit) {
            var index = input[at] & 0xFF;
            var value = input[at + 1] & 0xFF;
            at += 2;
            if (index >= 32) {
                throw new MediaException.Malformed("invalid DAC table index " + index);
            }
            if (index >= 16) {
                arithmeticAcThreshold[index - 16] = value;
            } else {
                arithmeticDcLower[index] = value & 15;
                arithmeticDcUpper[index] = value >>> 4;
                if ((value & 15) > (value >>> 4)) {
                    throw new MediaException.Malformed("invalid DAC conditioning value " + value);
                }
            }
        }
        position = limit;
    }

    /**
     * Parses a DNL segment outside the DNL lookup of {@link #resolveDnl()}; its value is ignored
     * once the height is known.
     */
    private void parseDnl() {
        var length = segmentLength(position);
        if (length != 4) {
            throw new MediaException.Malformed("invalid DNL segment length " + length);
        }
        position += length;
    }

    /**
     * Parses a SOF segment.
     *
     * @param marker the SOF marker code
     */
    private void parseSof(int marker) {
        if (frame != null && image == null) {
            throw new MediaException.Malformed("duplicate JPEG frame header");
        }
        var header = markerAt;
        if (wideRestartInterval && marker != 0xF7) {
            throw new MediaException.Malformed("a DRI segment before a frame other than JPEG-LS has a restart"
                    + " interval wider than 2 bytes");
        }
        var parsed = parseFrameHeader(marker);
        if (image != null) {
            checkHierarchicalFrame(parsed);
        }
        frame = parsed;
        frameStart = header;
    }

    /**
     * Parses the DHP segment of a hierarchical image.
     */
    private void parseDhp() {
        if (image != null || frame != null) {
            throw new MediaException.Malformed("JPEG DHP segment after a frame header or a previous DHP segment");
        }
        image = parseFrameHeader(0xDE);
    }

    /**
     * Parses an EXP segment.
     */
    private void parseExp() {
        var length = segmentLength(position);
        if (length != 3) {
            throw new MediaException.Malformed("invalid EXP segment length " + length);
        }
        if (expansion >= 0) {
            throw new MediaException.Malformed("two EXP segments precede one hierarchical JPEG frame");
        }
        var value = input[position + 2] & 0xFF;
        var horizontal = value >>> 4;
        var vertical = value & 15;
        if (horizontal > 1 || vertical > 1) {
            throw new MediaException.Malformed("invalid EXP expansion " + horizontal + "x" + vertical);
        }
        expansion = (horizontal << 1) | vertical;
        position += length;
    }

    /**
     * Checks a frame header of a hierarchical image against its DHP segment (T.81 B.3.1).
     *
     * @param parsed the frame header
     * @throws MediaException.Malformed if the frame is not a frame of the image
     */
    private void checkHierarchicalFrame(JpegFrame parsed) {
        var marker = parsed.marker;
        if (marker < 0xC0 || marker > 0xCF) {
            throw new MediaException.Malformed("a hierarchical JPEG image holds a frame of SOF marker 0x"
                    + Integer.toHexString(marker));
        }
        if (parsed.precision != image.precision) {
            throw new MediaException.Malformed("hierarchical JPEG frame of precision " + parsed.precision
                    + " in an image of precision " + image.precision);
        }
        if (parsed.width > image.width || (image.height != 0 && parsed.height > image.height)) {
            throw new MediaException.Malformed("hierarchical JPEG frame of " + parsed.width + "x" + parsed.height
                    + " samples exceeds the " + image.width + "x" + image.height + " image");
        }
        for (var component : parsed.components) {
            if (imageIndex(component.id) < 0) {
                throw new MediaException.Malformed("hierarchical JPEG frame codes component " + component.id
                        + ", which the DHP segment does not define");
            }
        }
    }

    /**
     * {@return the index of a component of the completed image of a hierarchical image, or {@code -1}}
     *
     * @param id the component identifier
     */
    int imageIndex(int id) {
        for (var i = 0; i < image.components.length; i++) {
            if (image.components[i].id == id) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Parses a frame header segment (SOF or DHP).
     *
     * @param marker the marker code
     * @return the frame
     */
    private JpegFrame parseFrameHeader(int marker) {
        var length = segmentLength(position);
        if (length < 8) {
            throw new MediaException.Malformed("truncated SOF segment");
        }
        var at = position + 2;
        var precision = input[at] & 0xFF;
        var height = u16(at + 1);
        var width = u16(at + 3);
        var count = input[at + 5] & 0xFF;
        if ((width == 0 && marker != 0xF7) || count == 0) {
            throw new MediaException.Malformed("empty JPEG frame: " + width + "x" + height + " with " + count + " components");
        }
        if (length != 8 + 3 * count) {
            throw new MediaException.Malformed("SOF segment length " + length + " does not match " + count + " components");
        }
        var components = new JpegComponent[count];
        for (var i = 0; i < count; i++) {
            var c = at + 6 + 3 * i;
            var sampling = input[c + 1] & 0xFF;
            var h = sampling >>> 4;
            var v = sampling & 15;
            var tq = input[c + 2] & 0xFF;
            if (h < 1 || h > 4 || v < 1 || v > 4) {
                throw new MediaException.Malformed("invalid sampling factors " + h + "x" + v);
            }
            if (tq > 3) {
                throw new MediaException.Malformed("invalid quantization table selector " + tq);
            }
            components[i] = new JpegComponent(input[c] & 0xFF, h, v, tq, i);
        }
        position += length;
        return new JpegFrame(marker, precision, width, height, components);
    }

    /**
     * Parses an application segment, keeping the metadata this reader understands.
     *
     * @param marker the APPn marker code
     */
    private void parseApp(int marker) {
        var length = segmentLength(position);
        var at = position + 2;
        var size = length - 2;
        var beforeScan = firstScan < 0;
        switch (marker) {
            case 0xE0 -> {
                if (beforeScan && size >= 14 && matches(at, "JFIF\0")) {
                    if (!jfif) {
                        jfifDensity = jfifDensity(input[at + 7] & 0xFF, u16(at + 8), u16(at + 10));
                    }
                    jfif = true;
                }
            }
            case 0xE1 -> {
                if (exif == null && JpegExif.prefixLength(input, at, size) != 0) {
                    exif = Arrays.copyOfRange(input, at + JpegExif.PREFIX, at + size);
                } else if (JpegXmp.startsWith(input, at, size, JpegXmp.XMP_ID)) {
                    if (xmp == null) {
                        xmp = Arrays.copyOfRange(input, at + JpegXmp.XMP_ID.length, at + size);
                    }
                } else if (JpegXmp.startsWith(input, at, size, JpegXmp.EXTENDED_XMP_ID)) {
                    extendedXmp.add(Arrays.copyOfRange(input, at, at + size));
                }
            }
            case 0xE2 -> icc.add(input, at, size);
            case 0xE8 -> {
                if (beforeScan && spiffColorSpace < 0 && size >= 30 && matches(at, "SPIFF\0")) {
                    spiffColorSpace = input[at + 18] & 0xFF;
                }
            }
            case 0xEE -> {
                if (beforeScan && size >= 12 && matches(at, "Adobe")) {
                    adobeTransform = input[at + 11] & 0xFF;
                }
            }
            default -> {
            }
        }
        position += length;
    }

    /**
     * Returns whether the input holds an ASCII signature at an index.
     *
     * @param at the index to compare at
     * @param signature the expected characters
     * @return {@code true} on a match
     */
    private boolean matches(int at, String signature) {
        for (var i = 0; i < signature.length(); i++) {
            if (input[at + i] != (byte) signature.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reads a big-endian 16-bit value.
     *
     * @param at the index of the first byte
     * @return the value
     */
    private int u16(int at) {
        return ((input[at] & 0xFF) << 8) | (input[at + 1] & 0xFF);
    }

    /**
     * Checks that the frame uses a coding process, precision, component count and sampling this
     * reader decodes.
     *
     * <p>The DCT processes (SOF0, SOF1, SOF2, SOF9, SOF10) take 8-bit and 12-bit precision; the
     * lossless processes (SOF3, SOF11) take any precision from 2 to 16. Every component count from 1 to
     * 255 is accepted; {@link #colorSpace()} tells which have a color space. A hierarchical image is checked
     * as the first frame after its DHP segment describes it; a differential frame (SOF5 to SOF7, SOF13 to SOF15)
     * outside hierarchical coding is malformed.
     *
     * @throws MediaException.Malformed if the precision is not a valid precision of the process, or the frame is
     * a differential frame without a DHP segment
     * @throws MediaException.Unsupported if it does not
     */
    public void checkSupported() {
        if (image != null) {
            checkHierarchical();
            return;
        }
        var marker = frame.marker;
        switch (marker) {
            case 0xC0, 0xC1, 0xC2, 0xC9, 0xCA -> {
                switch (frame.precision) {
                    case 8, 12 -> {
                    }
                    case 16 -> throw new MediaException.Unsupported("jpeg 16-bit precision", "16-bit JPEG is not supported");
                    default -> throw new MediaException.Malformed("invalid JPEG sample precision " + frame.precision);
                }
            }
            case 0xC3, 0xCB -> {
                if (frame.precision < 2 || frame.precision > 16) {
                    throw new MediaException.Malformed("invalid lossless JPEG sample precision " + frame.precision);
                }
            }
            case 0xC5, 0xC6, 0xC7, 0xCD, 0xCE, 0xCF -> throw new MediaException.Malformed("differential JPEG frame"
                    + " (SOF" + (marker - 0xC0) + ") without the DHP segment of hierarchical coding");
            case 0xF7 -> {
            }
            default -> throw new MediaException.Malformed("invalid SOF marker 0x" + Integer.toHexString(marker));
        }
    }

    /**
     * Checks the first frame of a hierarchical image: a non-differential frame of 8 or 12 bits coded with a DCT
     * process, or of 2 to 16 bits coded with a lossless process.
     *
     * @throws MediaException.Malformed if the first frame is differential or its precision is invalid
     * @throws MediaException.Unsupported if the DCT frames have 16-bit precision
     */
    private void checkHierarchical() {
        if (hierarchy != null) {
            return;
        }
        switch (frame.marker) {
            case 0xC0, 0xC1, 0xC2, 0xC9, 0xCA -> {
                switch (image.precision) {
                    case 8, 12 -> {
                    }
                    case 16 -> throw new MediaException.Unsupported("jpeg 16-bit precision", "16-bit JPEG is not supported");
                    default -> throw new MediaException.Malformed("invalid JPEG sample precision " + image.precision);
                }
            }
            case 0xC3, 0xCB -> {
                if (image.precision < 2 || image.precision > 16) {
                    throw new MediaException.Malformed("invalid lossless JPEG sample precision " + image.precision);
                }
            }
            default -> throw new MediaException.Malformed("hierarchical JPEG image starts with SOF marker 0x"
                    + Integer.toHexString(frame.marker) + ", not a non-differential frame");
        }
    }

    /**
     * Checks that the frame is supported and coded with a DCT process.
     *
     * @throws MediaException.Malformed if the precision is not a valid JPEG precision
     * @throws MediaException.Unsupported if the frame is unsupported or lossless
     */
    private void checkDct() {
        checkSupported();
        if (lossless()) {
            throw new MediaException.Unsupported("jpeg lossless", "lossless JPEG frames are not DCT coded");
        }
    }

    /**
     * {@return whether the frame's samples are not DCT coded: a lossless process (SOF3 Huffman, SOF11
     * arithmetic) or JPEG-LS (SOF55)}
     */
    public boolean lossless() {
        if (image != null) {
            return hierarchy().exact();
        }
        return frame.marker == 0xC3 || frame.marker == 0xCB || frame.marker == 0xF7;
    }

    /**
     * {@return whether the image is coded in hierarchical mode, with a DHP segment}
     */
    public boolean hierarchical() {
        return image != null;
    }

    /**
     * {@return the decoded components of a hierarchical image, decoding them on first use}
     */
    private HierarchicalImage hierarchy() {
        if (hierarchy == null) {
            readHeaders();
            checkHierarchical();
            hierarchy = HierarchicalImage.decode(this);
        }
        return hierarchy;
    }

    /**
     * {@return whether the frame is a JPEG-LS frame (SOF55)}
     */
    public boolean jpegLs() {
        return frame.marker == 0xF7;
    }

    /**
     * Opens the scan of a lossless frame, after checking the frame against the limits.
     *
     * @return the scan decoder, with the height a DNL segment defines when the frame header declares
     * none
     * @throws IllegalStateException if the frame is not lossless
     * @throws MediaException.Malformed if a header is invalid
     * @throws MediaException.Unsupported if the frame uses an unsupported feature
     * @throws MediaException.LimitExceeded if the frame exceeds the limits
     */
    LosslessSamples openLossless() {
        readHeaders();
        checkSupported();
        if (!lossless()) {
            throw new IllegalStateException("the frame is not lossless");
        }
        if (image != null) {
            prepareImage();
            return hierarchy().samples(image);
        }
        prepareFrame();
        if (jpegLs != null) {
            return jpegLs;
        }
        return new LosslessJpeg(input, start, end - start, 4, frame.height, limits);
    }

    /**
     * Returns the color space of the frame, inferred as libjpeg does.
     *
     * <p>One component is gray. Three components are YCbCr when a JFIF segment is present; else an
     * Adobe transform of 0 means RGB and any other transform YCbCr; else component identifiers
     * {@code 'R'}, {@code 'G'}, {@code 'B'} mean RGB and anything else YCbCr. Four components are
     * YCCK when an Adobe segment declares a non-zero transform and CMYK otherwise. Any other count
     * is {@link JpegColorSpace#UNKNOWN}.
     *
     * @return the color space
     */
    public JpegColorSpace colorSpace() {
        var components = frame().components;
        if (jpegLs != null) {
            return jpegLsColorSpace();
        }
        return switch (components.length) {
            case 1 -> JpegColorSpace.GRAY;
            case 3 -> {
                if (jfif) {
                    yield JpegColorSpace.YCBCR;
                }
                if (adobeTransform >= 0) {
                    yield adobeTransform == 0 ? JpegColorSpace.RGB : JpegColorSpace.YCBCR;
                }
                if (components[0].id == 'R' && components[1].id == 'G' && components[2].id == 'B') {
                    yield JpegColorSpace.RGB;
                }
                yield JpegColorSpace.YCBCR;
            }
            case 4 -> adobeTransform > 0 ? JpegColorSpace.YCCK : JpegColorSpace.CMYK;
            default -> JpegColorSpace.UNKNOWN;
        };
    }

    /**
     * Returns the color space of a JPEG-LS frame.
     *
     * <p>A palette image or a frame carrying an HP color transform is RGB. Otherwise one component is gray;
     * three components are YCbCr when a JFIF segment is present, else as the SPIFF header declares (YCbCr for
     * its three Y'CbCr color spaces, RGB), else as an Adobe transform declares, else RGB; four components are
     * YCCK when an Adobe segment declares a non-zero transform or the SPIFF header declares YCCK, and CMYK
     * otherwise. Any other count is {@link JpegColorSpace#UNKNOWN}.
     *
     * @return the color space
     */
    private JpegColorSpace jpegLsColorSpace() {
        if (jpegLs.palette() || jpegLs.colorTransform() != 0) {
            return JpegColorSpace.RGB;
        }
        return switch (frame.components.length) {
            case 1 -> JpegColorSpace.GRAY;
            case 3 -> {
                if (jfif || spiffColorSpace == 1 || spiffColorSpace == 3 || spiffColorSpace == 4) {
                    yield JpegColorSpace.YCBCR;
                }
                if (spiffColorSpace == 10) {
                    yield JpegColorSpace.RGB;
                }
                if (adobeTransform >= 0) {
                    yield adobeTransform == 0 ? JpegColorSpace.RGB : JpegColorSpace.YCBCR;
                }
                yield JpegColorSpace.RGB;
            }
            case 4 -> adobeTransform > 0 || spiffColorSpace == 13 ? JpegColorSpace.YCCK : JpegColorSpace.CMYK;
            default -> JpegColorSpace.UNKNOWN;
        };
    }

    /**
     * Returns the binary logarithm of the largest size reduction, among 1/1, 1/2, 1/4 and 1/8,
     * whose output is still at least the requested size.
     *
     * @param minWidth the smallest acceptable width, or zero
     * @param minHeight the smallest acceptable height, or zero
     * @return the shift, zero when both hints are zero
     */
    public int chooseScale(int minWidth, int minHeight) {
        if ((minWidth <= 0 && minHeight <= 0) || image != null) {
            return 0;
        }
        var shift = 0;
        for (var candidate = 1; candidate <= 3; candidate++) {
            var width = JpegFrame.ceilDiv(frame.width, 1 << candidate);
            var height = JpegFrame.ceilDiv(frame.height, 1 << candidate);
            if (width >= minWidth && height >= minHeight) {
                shift = candidate;
            }
        }
        return shift;
    }

    /**
     * Decodes every scan into 8-bit sample planes.
     *
     * <p>The samples of a 12-bit frame are rescaled as {@code round(v * 255 / 4095)}.
     *
     * @param scaleShift the binary logarithm of the size reduction, from 0 to 3
     * @return the planes
     * @throws MediaException.Malformed if a header is invalid or no MCU could be decoded
     * @throws MediaException.Unsupported if the frame uses an unsupported feature or is lossless
     * @throws MediaException.LimitExceeded if the frame exceeds the limits
     */
    public JpegPlanes decodePlanes(int scaleShift) {
        return decodePlanes(scaleShift, null);
    }

    /**
     * Returns whether {@link #decodePlanes(int, Region)} and {@link #decodeWidePlanes(int, Region)} decode a window
     * of the frame: a DCT frame that is not part of a hierarchical image.
     *
     * @return whether a window decodes
     */
    public boolean windowable() {
        readHeaders();
        return image == null && !frame.lossless();
    }

    /**
     * Returns the MCUs of the frame that cover a rectangle of the picture decoded at a size reduction.
     *
     * @param scaleShift the binary logarithm of the size reduction, from 0 to 3
     * @param rectangle the rectangle, in pixels of the reduced picture, inside it
     * @return the left, top, right and bottom MCU column and row, the last two exclusive
     */
    private int[] mcuWindow(int scaleShift, Region rectangle) {
        var mcuWidth = (8 * frame.hMax) >> scaleShift;
        var mcuHeight = (8 * frame.vMax) >> scaleShift;
        return new int[]{rectangle.x() / mcuWidth, rectangle.y() / mcuHeight,
                Math.min(frame.mcusWide, JpegFrame.ceilDiv((long) rectangle.x() + rectangle.width(), mcuWidth)),
                Math.min(frame.mcusHigh, JpegFrame.ceilDiv((long) rectangle.y() + rectangle.height(), mcuHeight))};
    }

    /**
     * Returns the top-left corner of the window of whole MCUs that {@link #decodePlanes(int, Region)} decodes for
     * a rectangle.
     *
     * @param scaleShift the binary logarithm of the size reduction, from 0 to 3
     * @param rectangle the rectangle, in pixels of the reduced picture, inside it
     * @return the left and top edges of the window, in pixels of the reduced picture
     */
    public int[] windowOrigin(int scaleShift, Region rectangle) {
        readHeaders();
        frame.computeLayout();
        var mcus = mcuWindow(scaleShift, rectangle);
        return new int[]{mcus[0] * ((8 * frame.hMax) >> scaleShift), mcus[1] * ((8 * frame.vMax) >> scaleShift)};
    }

    /**
     * Returns the frame of a window of whole MCUs of the frame: its size and the sizes of its components.
     *
     * @param mcus the window, as {@link #mcuWindow} returns it
     * @return the frame, laid out
     */
    private JpegFrame windowFrame(int[] mcus) {
        var mcuWidth = 8 * frame.hMax;
        var mcuHeight = 8 * frame.vMax;
        var width = (int) (Math.min(frame.width, (long) mcus[2] * mcuWidth) - (long) mcus[0] * mcuWidth);
        var height = (int) (Math.min(frame.height, (long) mcus[3] * mcuHeight) - (long) mcus[1] * mcuHeight);
        var components = new JpegComponent[frame.components.length];
        for (var i = 0; i < components.length; i++) {
            var component = frame.components[i];
            components[i] = new JpegComponent(component.id, component.h, component.v, component.quantSelector,
                    component.index);
        }
        var window = new JpegFrame(frame.marker, frame.precision, width, height, components);
        window.computeLayout();
        return window;
    }

    /**
     * Allocates the sample planes of every component, of the whole frame or of a window of MCUs.
     *
     * @param scaleShift the binary logarithm of the size reduction
     * @param mcus the window, or {@code null} for the whole frame
     * @param strides receives the row stride of each plane
     * @param wide whether the planes hold 16-bit samples
     * @return the sizes of the planes, in samples
     */
    private long[] planeSizes(int scaleShift, int[] mcus, int[] strides, boolean wide) {
        var components = frame.components;
        var size = 8 >> scaleShift;
        var sizes = new long[components.length];
        for (var i = 0; i < components.length; i++) {
            var component = components[i];
            var columns = mcus == null ? component.paddedBlocksWide
                    : Math.min(mcus[2] * component.h, component.paddedBlocksWide) - mcus[0] * component.h;
            var rows = mcus == null ? component.paddedBlocksHigh
                    : Math.min(mcus[3] * component.v, component.paddedBlocksHigh) - mcus[1] * component.v;
            strides[i] = columns * size;
            sizes[i] = (long) strides[i] * rows * size;
            LimitChecks.checkAllocation(limits, wide ? sizes[i] * 2 : sizes[i]);
        }
        return sizes;
    }

    /**
     * Decodes every scan into 8-bit sample planes of the whole frame or of the MCUs that cover a rectangle of the
     * picture.
     *
     * <p>With a rectangle, the planes and their frame describe the window of whole MCUs that covers it, from
     * {@link #windowOrigin}, and hold the samples the whole decode holds there. A sequential scan decodes the
     * MCUs outside the window without transforming them, stops after its last MCU row, and skips the restart
     * intervals that hold none of its MCUs when the stream allows decoding them apart; a progressive frame
     * transforms only the blocks of the window. The limits bound the window rather than the frame, except that
     * a progressive or 12-bit frame, which keeps the coefficients of the whole frame, is bounded as a whole.
     *
     * @param scaleShift the binary logarithm of the size reduction, from 0 to 3
     * @param rectangle the rectangle, in pixels of the reduced picture, inside it, or {@code null} for the whole
     * frame; only when {@link #windowable()} holds
     * @return the planes
     * @throws MediaException.Malformed if a header is invalid or no MCU could be decoded
     * @throws MediaException.Unsupported if the frame uses an unsupported feature or is lossless
     * @throws MediaException.LimitExceeded if the frame, or the window, exceeds the limits
     */
    public JpegPlanes decodePlanes(int scaleShift, Region rectangle) {
        readHeaders();
        checkDct();
        if (image != null) {
            prepareImage();
            return hierarchy().planes(image, false);
        }
        var mcus = prepareWindow(scaleShift, rectangle);
        if (frame.precision == 12) {
            return decodeTwelveBit(scaleShift, false, mcus);
        }
        var components = frame.components;
        var planes = new byte[components.length][];
        var strides = new int[components.length];
        var sizes = planeSizes(scaleShift, mcus, strides, false);
        for (var i = 0; i < components.length; i++) {
            planes[i] = new byte[(int) sizes[i]];
        }
        if (frame.progressive()) {
            var coefficients = allocateCoefficients();
            var target = new DecodeTarget(components.length, null, null, coefficients, allocateMasks(), scaleShift);
            decodeScans(target);
            transformCoefficients(target, planes, strides, scaleShift, mcus);
        } else {
            var target = new DecodeTarget(components.length, planes, strides, null, null, scaleShift, false, mcus);
            var seen = decodeScans(target);
            for (var i = 0; i < components.length; i++) {
                if (!seen[i]) {
                    Arrays.fill(planes[i], (byte) 128);
                }
            }
        }
        return new JpegPlanes(mcus == null ? frame : windowFrame(mcus), planes, strides, scaleShift);
    }

    /**
     * Lays the frame out and checks it, or the window of MCUs covering a rectangle, against the limits.
     *
     * @param scaleShift the binary logarithm of the size reduction
     * @param rectangle the rectangle, or {@code null} for the whole frame
     * @return the window, or {@code null} for the whole frame
     * @throws MediaException.LimitExceeded if the frame or the window exceeds the limits
     */
    private int[] prepareWindow(int scaleShift, Region rectangle) {
        if (rectangle == null) {
            prepareFrame();
            return null;
        }
        LimitChecks.checkChannels(limits, frame.components.length);
        if (frame.progressive() || frame.precision == 12) {
            // the coefficients of the whole frame are kept
            LimitChecks.checkPicture(limits, frame.width, frame.height);
        }
        frame.computeLayout();
        var mcus = mcuWindow(scaleShift, rectangle);
        var window = windowFrame(mcus);
        LimitChecks.checkPicture(limits, JpegFrame.ceilDiv(window.width, 1 << scaleShift),
                JpegFrame.ceilDiv(window.height, 1 << scaleShift));
        return mcus;
    }

    /**
     * Decodes every scan of a 12-bit frame into 12-bit sample planes.
     *
     * @param scaleShift the binary logarithm of the size reduction, from 0 to 3
     * @return the {@linkplain JpegPlanes#wide() wide} planes
     * @throws IllegalStateException if the frame is a DCT frame of 8-bit precision
     * @throws MediaException.Malformed if a header is invalid or no MCU could be decoded
     * @throws MediaException.Unsupported if the frame uses an unsupported feature or is lossless
     * @throws MediaException.LimitExceeded if the frame exceeds the limits
     */
    public JpegPlanes decodeWidePlanes(int scaleShift) {
        return decodeWidePlanes(scaleShift, null);
    }

    /**
     * Decodes every scan of a 12-bit frame into 12-bit sample planes of the whole frame or of the MCUs that cover a
     * rectangle of the picture, as {@link #decodePlanes(int, Region)} describes.
     *
     * @param scaleShift the binary logarithm of the size reduction, from 0 to 3
     * @param rectangle the rectangle, in pixels of the reduced picture, inside it, or {@code null} for the whole
     * frame; only when {@link #windowable()} holds
     * @return the {@linkplain JpegPlanes#wide() wide} planes
     * @throws IllegalStateException if the frame is a DCT frame of 8-bit precision
     * @throws MediaException.Malformed if a header is invalid or no MCU could be decoded
     * @throws MediaException.Unsupported if the frame uses an unsupported feature or is lossless
     * @throws MediaException.LimitExceeded if the frame, or the window, exceeds the limits
     */
    public JpegPlanes decodeWidePlanes(int scaleShift, Region rectangle) {
        readHeaders();
        checkDct();
        if (frame().precision != 12) {
            throw new IllegalStateException("the frame has " + frame().precision + "-bit precision, not 12");
        }
        if (image != null) {
            prepareImage();
            return hierarchy().planes(image, true);
        }
        return decodeTwelveBit(scaleShift, true, prepareWindow(scaleShift, rectangle));
    }

    /**
     * Decodes every scan of a 12-bit frame into coefficients and transforms them into planes.
     *
     * <p>A component no scan referenced has an all-zero quantization table, so it transforms to
     * mid-gray, as do the blocks of MCUs without decodable data.
     *
     * @param scaleShift the binary logarithm of the size reduction
     * @param wide whether the planes keep 12-bit samples rather than 8-bit ones
     * @param mcus the window of MCUs the planes hold, or {@code null} for the whole frame
     * @return the planes
     */
    private JpegPlanes decodeTwelveBit(int scaleShift, boolean wide, int[] mcus) {
        var components = frame.components;
        var strides = new int[components.length];
        var widePlanes = wide ? new short[components.length][] : null;
        var planes = wide ? null : new byte[components.length][];
        var sizes = planeSizes(scaleShift, mcus, strides, wide);
        for (var i = 0; i < components.length; i++) {
            if (wide) {
                widePlanes[i] = new short[(int) sizes[i]];
            } else {
                planes[i] = new byte[(int) sizes[i]];
            }
        }
        var target = new DecodeTarget(components.length, null, null, allocateCoefficients(),
                frame.progressive() ? allocateMasks() : null, scaleShift);
        decodeScans(target);
        for (var i = 0; i < components.length; i++) {
            var component = components[i];
            var quant = naturalQuant(target.zigzagQuant[i]);
            var coefficients = target.coefficients[i];
            var widePlane = wide ? widePlanes[i] : null;
            var plane = wide ? null : planes[i];
            var stride = strides[i];
            var area = blockArea(component, mcus);
            var paddedWide = component.paddedBlocksWide;
            var rows = area[3] - area[1];
            var bands = ParallelBands.bands(rows, (long) rows * (area[2] - area[0]) * BLOCK_WORK);
            ParallelBands.run(bands, band -> transformRows12(coefficients, paddedWide, area, quant, widePlane,
                    plane, stride, scaleShift, area[1] + ParallelBands.start(band, bands, rows),
                    area[1] + ParallelBands.start(band + 1, bands, rows)));
        }
        var layout = mcus == null ? frame : windowFrame(mcus);
        return wide ? new JpegPlanes(layout, widePlanes, strides, scaleShift)
                : new JpegPlanes(layout, planes, strides, scaleShift);
    }

    /**
     * Returns the blocks of a component a transform covers: the blocks of the component with data, or those of a
     * window of MCUs.
     *
     * @param component the frame component
     * @param mcus the window, or {@code null} for the whole frame
     * @return the left, top, right and bottom block column and row (the last two exclusive), the left and top ones
     * also the block at the origin of the plane
     */
    private static int[] blockArea(JpegComponent component, int[] mcus) {
        var columns = Math.min(component.paddedBlocksWide, component.blocksWide);
        var rows = Math.min(component.paddedBlocksHigh, component.blocksHigh);
        if (mcus == null) {
            return new int[]{0, 0, columns, rows};
        }
        return new int[]{mcus[0] * component.h, mcus[1] * component.v, Math.min(columns, mcus[2] * component.h),
                Math.min(rows, mcus[3] * component.v)};
    }

    /**
     * Transforms the block rows {@code [first, last)} of one component of a 12-bit frame into its
     * 12-bit plane or, rescaled, its 8-bit plane.
     *
     * @param coefficients the component's stored coefficients, natural order
     * @param paddedWide the number of blocks per row of the coefficient buffer
     * @param area the blocks to transform, as {@link #blockArea} returns them
     * @param quant the natural-order dequantization table
     * @param widePlane the 12-bit destination plane, or {@code null}
     * @param plane the 8-bit destination plane, used when {@code widePlane} is {@code null}
     * @param stride the plane stride
     * @param scaleShift the binary logarithm of the size reduction
     * @param first the first block row
     * @param last the block row after the last one
     */
    private static void transformRows12(short[] coefficients, int paddedWide, int[] area, int[] quant,
                                        short[] widePlane, byte[] plane, int stride, int scaleShift, int first,
                                        int last) {
        var block = new int[64];
        var size = 8 >> scaleShift;
        var scratch = widePlane == null ? new short[size * size] : null;
        var narrow = Idct12.EIGHT_BIT;
        for (var by = first; by < last; by++) {
            for (var bx = area[0]; bx < area[2]; bx++) {
                var base = (by * paddedWide + bx) * 64;
                var offset = (by - area[1]) * size * stride + (bx - area[0]) * size;
                if (widePlane != null) {
                    Idct12.transformBlock(coefficients, base, quant, block, widePlane, offset, stride, scaleShift);
                    continue;
                }
                Idct12.transformBlock(coefficients, base, quant, block, scratch, 0, size, scaleShift);
                for (var row = 0; row < size; row++) {
                    var out = offset + row * stride;
                    for (var x = 0; x < size; x++) {
                        plane[out + x] = narrow[scratch[row * size + x]];
                    }
                }
            }
        }
    }

    /**
     * Decodes every scan into quantized coefficients, without transforming them.
     *
     * @return the coefficients and the quantization tables they refer to
     * @throws MediaException.Malformed if a header is invalid or no MCU could be decoded
     * @throws MediaException.Unsupported if the frame uses an unsupported feature or is lossless, or the image is
     * hierarchical (rejected before any of its frames is decoded)
     * @throws MediaException.LimitExceeded if the frame exceeds the limits
     */
    public JpegCoefficients decodeCoefficients() {
        readHeaders();
        if (image != null) {
            throw new MediaException.Unsupported("jpeg hierarchical",
                    "the coefficients of a hierarchical JPEG image are not available");
        }
        checkDct();
        prepareFrame();
        var components = frame.components;
        var coefficients = allocateCoefficients();
        var target = new DecodeTarget(components.length, null, null, coefficients,
                frame.progressive() ? allocateMasks() : null, 0);
        decodeScans(target);
        var quantization = new int[components.length][];
        for (var i = 0; i < components.length; i++) {
            quantization[i] = naturalQuant(target.zigzagQuant[i]);
        }
        return new JpegCoefficients(frame, coefficients, quantization);
    }

    /**
     * Computes the frame layout and checks it against the limits.
     */
    private void prepareFrame() {
        LimitChecks.checkPicture(limits, frame.width, frame.height);
        LimitChecks.checkChannels(limits, frame.components.length);
        frame.computeLayout();
    }

    /**
     * Computes the layout of the completed image of a hierarchical image and checks it against the limits.
     */
    private void prepareImage() {
        var height = hierarchy().height();
        if (image.height == 0) {
            image.setHeight(height);
        }
        LimitChecks.checkPicture(limits, image.width, image.height);
        LimitChecks.checkChannels(limits, image.components.length);
        image.computeLayout();
    }

    /**
     * {@return the frame being decoded: the current frame of a hierarchical image}
     */
    JpegFrame codedFrame() {
        return frame;
    }

    /**
     * {@return the frame header of the completed image of a hierarchical image}
     */
    JpegFrame imageFrame() {
        return image;
    }

    /**
     * Returns the expansion of the EXP segment before the current frame and forgets it.
     *
     * @return the horizontal expansion in bit 1 and the vertical one in bit 0, or {@code -1} for none
     */
    int takeExpansion() {
        var value = expansion;
        expansion = -1;
        return value;
    }

    /**
     * Moves to the next frame of a hierarchical image, after the scans of the current one were decoded or
     * skipped: reads the segments up to its first scan.
     *
     * @return {@code false} when no frame follows
     * @throws MediaException.Malformed if a segment is invalid, or a scan has no frame header of its own
     */
    boolean nextFrame() {
        frameOpen = false;
        var previous = frame;
        if (!parseUntilScan()) {
            return false;
        }
        if (frame == previous) {
            throw new MediaException.Malformed("hierarchical JPEG scan without a frame header of its own");
        }
        firstScan = position;
        frameOpen = true;
        if (frame.height == 0) {
            resolveDnl();
        }
        return true;
    }

    /**
     * Decodes the scans of the current DCT-coded frame of a hierarchical image into the samples of each frame
     * component, each {@code width * height} in raster order.
     *
     * @param differential whether the frame is differential: its samples are then the unshifted, unlimited
     * differences
     * @return the samples of each frame component
     * @throws MediaException.Malformed if a scan header is invalid or no MCU could be decoded
     * @throws MediaException.LimitExceeded if the frame exceeds the limits
     */
    int[][] decodeDctFrame(boolean differential) {
        prepareFrame();
        var components = frame.components;
        var target = new DecodeTarget(components.length, null, null, allocateCoefficients(),
                frame.progressive() ? allocateMasks() : null, 0, differential);
        decodeScans(target);
        var samples = new int[components.length][];
        var precision = frame.precision;
        for (var i = 0; i < components.length; i++) {
            var component = components[i];
            var width = component.width;
            var height = component.height;
            LimitChecks.checkAllocation(limits, 4L * width * height);
            var plane = new int[width * height];
            samples[i] = plane;
            var quant = naturalQuant(target.zigzagQuant[i]);
            var coefficients = target.coefficients[i];
            var paddedWide = component.paddedBlocksWide;
            var columns = component.blocksWide;
            var rows = component.blocksHigh;
            var bands = ParallelBands.bands(rows, (long) rows * columns * BLOCK_WORK);
            ParallelBands.run(bands, band -> {
                var block = new int[64];
                var last = ParallelBands.start(band + 1, bands, rows);
                for (var by = ParallelBands.start(band, bands, rows); by < last; by++) {
                    for (var bx = 0; bx < columns; bx++) {
                        var base = (by * paddedWide + bx) * 64;
                        for (var k = 0; k < 64; k++) {
                            block[k] = coefficients[base + k] * quant[k];
                        }
                        HierarchicalIdct.transform(block, precision, differential, plane, by * 8 * width + bx * 8,
                                width, Math.min(8, width - bx * 8), Math.min(8, height - by * 8));
                    }
                }
            });
        }
        return samples;
    }

    /**
     * Skips the scans of the current frame of a hierarchical image, keeping the tables they define, after another
     * decoder has decoded them.
     *
     * @throws MediaException.Malformed if a scan header is invalid
     */
    void skipFrame() {
        position = firstScan;
        do {
            parseSos();
            segments.load(input, position, end);
            scansDecoded = true;
            position = segments.scanEnd();
        } while (parseUntilScan());
    }

    /**
     * {@return the index of the {@code 0xFF} of the SOF marker of the current frame}
     */
    int frameStart() {
        return frameStart;
    }

    /**
     * {@return the input}
     */
    byte[] input() {
        return input;
    }

    /**
     * {@return the index of the SOI marker}
     */
    int start() {
        return start;
    }

    /**
     * {@return the index after the input}
     */
    int end() {
        return end;
    }

    /**
     * {@return the resource bounds}
     */
    MediaLimits limits() {
        return limits;
    }

    /**
     * Allocates a zeroed coefficient buffer for every component.
     *
     * @return the buffers
     */
    private short[][] allocateCoefficients() {
        var components = frame.components;
        var coefficients = new short[components.length][];
        for (var i = 0; i < components.length; i++) {
            var count = (long) components[i].paddedBlocksWide * components[i].paddedBlocksHigh * 64;
            LimitChecks.checkAllocation(limits, count * 2);
            coefficients[i] = new short[(int) count];
        }
        return coefficients;
    }

    /**
     * Allocates the per-block nonzero masks progressive Huffman decoding keeps, or none for
     * arithmetic coding.
     *
     * @return the masks, or {@code null}
     */
    private long[][] allocateMasks() {
        if (frame.arithmetic()) {
            return null;
        }
        var components = frame.components;
        var masks = new long[components.length][];
        for (var i = 0; i < components.length; i++) {
            var count = (long) components[i].paddedBlocksWide * components[i].paddedBlocksHigh;
            LimitChecks.checkAllocation(limits, count * 8);
            masks[i] = new long[(int) count];
        }
        return masks;
    }

    /**
     * Decodes every scan, starting at the first one.
     *
     * @param target where decoded blocks go
     * @return which frame components appeared in at least one scan
     * @throws MediaException.Malformed if a scan header is invalid or no MCU could be decoded
     */
    private boolean[] decodeScans(DecodeTarget target) {
        var seen = new boolean[frame.components.length];
        position = firstScan;
        var decoded = 0L;
        do {
            var scan = parseSos();
            segments.load(input, position, end);
            for (var component : scan.components) {
                if (target.zigzagQuant[component.index] == null) {
                    var table = quantTables[component.quantSelector];
                    if (table == null) {
                        throw new MediaException.Malformed("missing quantization table " + component.quantSelector);
                    }
                    target.zigzagQuant[component.index] = zigzagQuant(table);
                }
                seen[component.index] = true;
            }
            if (!skipsScan(scan, target)) {
                decoded += decodeScan(scan, target);
            }
            scansDecoded = true;
            position = segments.scanEnd();
        } while (parseUntilScan());
        if (decoded == 0) {
            throw new MediaException.Malformed("JPEG stream holds no decodable entropy-coded data");
        }
        return seen;
    }

    /**
     * Returns whether a scan cannot change the output and is skipped without being decoded.
     *
     * <p>That is the case of the AC scans of a progressive frame decoded at 1/8 size, whose 1x1
     * inverse transform reads only the DC coefficient. Since a skipped scan decodes no MCU, a
     * progressive stream whose DC scans hold no decodable data fails at that size even when its
     * AC scans are intact.
     *
     * @param scan the scan being decoded
     * @param target where decoded blocks go
     * @return whether the scan is skipped
     */
    private boolean skipsScan(JpegScan scan, DecodeTarget target) {
        return frame.progressive() && target.scaleShift == 3 && scan.ss != 0;
    }

    /**
     * Decodes one scan with the entropy decoder of the frame.
     *
     * @param scan the scan being decoded
     * @param target where decoded blocks go
     * @return the number of MCUs decoded from data
     */
    private long decodeScan(JpegScan scan, DecodeTarget target) {
        var count = scan.components.length;
        if (frame.arithmetic()) {
            if (arithmetic == null) {
                arithmetic = new ArithmeticScanDecoder(segments);
            }
            var dcLower = new int[count];
            var dcUpper = new int[count];
            var acThreshold = new int[count];
            for (var ci = 0; ci < count; ci++) {
                var dc = scan.dcSelectors[ci];
                var ac = scan.acSelectors[ci];
                dcLower[ci] = arithmeticDcLower[dc];
                dcUpper[ci] = arithmeticDcUpper[dc];
                acThreshold[ci] = arithmeticAcThreshold[ac];
            }
            return arithmetic.decode(scan, dcLower, dcUpper, acThreshold, restartInterval, target, frame.progressive());
        }
        var dc = new HuffmanDecodingTable[count];
        var ac = new HuffmanDecodingTable[count];
        var progressive = frame.progressive();
        var needsDc = !progressive || (scan.ss == 0 && scan.ah == 0);
        var needsAc = !progressive || scan.ss != 0;
        for (var ci = 0; ci < count; ci++) {
            if (needsDc) {
                dc[ci] = dcTable(scan.dcSelectors[ci]);
            }
            if (needsAc) {
                ac[ci] = acTable(scan.acSelectors[ci]);
            }
        }
        if (huffman == null) {
            huffman = new HuffmanScanDecoder(segments, limits);
        }
        return huffman.decode(scan, dc, ac, restartInterval, target, progressive);
    }

    /**
     * Returns the decoding table of a DC slot, building it on first use.
     *
     * @param slot the table selector
     * @return the table
     * @throws MediaException.Malformed if the slot is out of range, undefined, or invalid
     */
    private HuffmanDecodingTable dcTable(int slot) {
        if (slot > 3 || dcSpecs[slot] == null) {
            throw new MediaException.Malformed("missing DC Huffman table " + slot);
        }
        if (dcDecoding[slot] == null) {
            dcDecoding[slot] = new HuffmanDecodingTable(dcSpecs[slot], true);
        }
        return dcDecoding[slot];
    }

    /**
     * Returns the decoding table of an AC slot, building it on first use.
     *
     * @param slot the table selector
     * @return the table
     * @throws MediaException.Malformed if the slot is out of range, undefined, or invalid
     */
    private HuffmanDecodingTable acTable(int slot) {
        if (slot > 3 || acSpecs[slot] == null) {
            throw new MediaException.Malformed("missing AC Huffman table " + slot);
        }
        if (acDecoding[slot] == null) {
            acDecoding[slot] = new HuffmanDecodingTable(acSpecs[slot], false);
        }
        return acDecoding[slot];
    }

    /**
     * Parses the SOS segment at {@link #position} and validates it against the frame.
     *
     * @return the scan
     * @throws MediaException.Malformed if the segment is invalid
     */
    private JpegScan parseSos() {
        var length = segmentLength(position);
        var at = position + 2;
        var count = input[at] & 0xFF;
        if (count < 1 || count > 4 || length != 6 + 2 * count) {
            throw new MediaException.Malformed("invalid SOS segment with " + count + " components and length " + length);
        }
        var components = new JpegComponent[count];
        var dcSelectors = new int[count];
        var acSelectors = new int[count];
        var frameComponents = frame.components;
        for (var i = 0; i < count; i++) {
            var id = input[at + 1 + 2 * i] & 0xFF;
            var tables = input[at + 2 + 2 * i] & 0xFF;
            JpegComponent match = null;
            for (var candidate : frameComponents) {
                if (candidate.id == id && !contains(components, i, candidate)) {
                    match = candidate;
                    break;
                }
            }
            if (match == null) {
                throw new MediaException.Malformed("SOS references unknown or repeated component " + id);
            }
            components[i] = match;
            dcSelectors[i] = tables >>> 4;
            acSelectors[i] = tables & 15;
        }
        var ss = input[at + 1 + 2 * count] & 0xFF;
        var se = input[at + 2 + 2 * count] & 0xFF;
        var approximation = input[at + 3 + 2 * count] & 0xFF;
        var ah = approximation >>> 4;
        var al = approximation & 15;
        position += length;
        if (frame.progressive()) {
            var bad = ss == 0 ? se != 0 : ss > se || se > 63 || count != 1;
            if (ah != 0 && al != ah - 1) {
                bad = true;
            }
            if (al > 13) {
                bad = true;
            }
            if (bad) {
                throw new MediaException.Malformed("invalid progressive scan parameters Ss=" + ss + " Se=" + se
                        + " Ah=" + ah + " Al=" + al);
            }
        } else {
            ss = 0;
            se = 63;
            ah = 0;
            al = 0;
        }
        if (count > 1) {
            var blocks = 0;
            for (var component : components) {
                blocks += component.h * component.v;
            }
            if (blocks > MAX_BLOCKS_IN_MCU) {
                throw new MediaException.Malformed("interleaved scan has " + blocks + " blocks per MCU, more than 10");
            }
        }
        return new JpegScan(frame, components, dcSelectors, acSelectors, ss, se, ah, al);
    }

    /**
     * Returns whether a component already appears among the first entries of an array.
     *
     * @param components the array
     * @param count the number of entries to examine
     * @param component the component to look for
     * @return {@code true} when present
     */
    private static boolean contains(JpegComponent[] components, int count, JpegComponent component) {
        for (var i = 0; i < count; i++) {
            if (components[i] == component) {
                return true;
            }
        }
        return false;
    }

    /**
     * Transforms the coefficients of a progressive frame into sample planes.
     *
     * <p>At full size and at 1/2 and 1/4 of it, each block row is dequantized into batches of
     * {@link BatchIdctKernel#LANES} horizontally adjacent blocks transformed together by
     * {@link BatchIdctKernel}; at 1/8 each block becomes its DC value. Without the Vector API each
     * block is transformed on its own, which is faster than the scalar batch transform. The block
     * rows of a large component are transformed in bands on several threads.
     *
     * @param target the decode target holding the coefficients and the latched tables
     * @param planes the destination planes
     * @param strides the plane strides
     * @param scaleShift the binary logarithm of the size reduction
     * @param mcus the window of MCUs the planes hold, or {@code null} for the whole frame
     */
    private void transformCoefficients(DecodeTarget target, byte[][] planes, int[] strides, int scaleShift,
                                       int[] mcus) {
        var components = frame.components;
        for (var i = 0; i < components.length; i++) {
            var component = components[i];
            var quant = naturalQuant(target.zigzagQuant[i]);
            var coefficients = target.coefficients[i];
            var plane = planes[i];
            var stride = strides[i];
            var area = blockArea(component, mcus);
            var rows = area[3] - area[1];
            var paddedWide = component.paddedBlocksWide;
            var bands = ParallelBands.bands(rows, (long) rows * (area[2] - area[0]) * BLOCK_WORK);
            ParallelBands.run(bands, band -> transformRows(coefficients, paddedWide, area, quant, plane, stride,
                    scaleShift, area[1] + ParallelBands.start(band, bands, rows),
                    area[1] + ParallelBands.start(band + 1, bands, rows)));
        }
    }

    /**
     * Transforms the block rows {@code [first, last)} of one component of a progressive frame
     * into its sample plane.
     *
     * @param coefficients the component's stored coefficients, natural order
     * @param paddedWide the number of blocks per row of the coefficient buffer
     * @param area the blocks to transform, as {@link #blockArea} returns them
     * @param quant the natural-order dequantization table
     * @param plane the destination plane
     * @param stride the plane stride
     * @param scaleShift the binary logarithm of the size reduction
     * @param first the first block row
     * @param last the block row after the last one
     */
    private static void transformRows(short[] coefficients, int paddedWide, int[] area, int[] quant, byte[] plane,
                                      int stride, int scaleShift, int first, int last) {
        var block = new int[64];
        var batch = new int[BatchIdctKernel.BATCH_LENGTH];
        var size = 8 >> scaleShift;
        var kernel = BatchIdctKernel.INSTANCE;
        var batches = scaleShift < 3 && BatchIdctKernel.batches();
        var left = area[0];
        var right = area[2];
        for (var by = first; by < last; by++) {
            var row = by - area[1];
            if (!batches) {
                for (var bx = left; bx < right; bx++) {
                    transformBlock(coefficients, (by * paddedWide + bx) * 64, quant, block, plane,
                            (row * stride + bx - left) * size, stride, scaleShift);
                }
                continue;
            }
            for (var bx = left; bx < right; bx += BatchIdctKernel.LANES) {
                var count = Math.min(BatchIdctKernel.LANES, right - bx);
                for (var lane = 0; lane < count; lane++) {
                    dequantize(coefficients, (by * paddedWide + bx + lane) * 64, quant, batch, lane);
                }
                var offset = row * size * stride + (bx - left) * size;
                switch (scaleShift) {
                    case 0 -> kernel.idct8(batch, count, plane, offset, stride);
                    case 1 -> kernel.idct4(batch, count, plane, offset, stride);
                    default -> kernel.idct2(batch, count, plane, offset, stride);
                }
            }
        }
    }

    /**
     * Dequantizes one block of stored coefficients into one lane of a {@link BatchIdctKernel}
     * batch.
     *
     * @param coefficients the stored coefficients, natural order
     * @param base the index of the block's first coefficient
     * @param quant the natural-order dequantization table
     * @param batch the batch, zero in the lane on entry
     * @param lane the lane of the block
     */
    private static void dequantize(short[] coefficients, int base, int[] quant, int[] batch, int lane) {
        for (var k = 0; k < 64; k++) {
            batch[k * BatchIdctKernel.LANES + lane] = coefficients[base + k] * quant[k];
        }
    }

    /**
     * Dequantizes and transforms one block of coefficients.
     *
     * @param coefficients the coefficient buffer
     * @param base the index of the block's first coefficient
     * @param quant the natural-order quantization table
     * @param block a scratch block, all zero on entry and on return
     * @param plane the destination plane
     * @param offset the index of the block's top-left sample
     * @param stride the plane stride
     * @param scaleShift the binary logarithm of the size reduction
     */
    static void transformBlock(short[] coefficients, int base, int[] quant, int[] block, byte[] plane, int offset,
                               int stride, int scaleShift) {
        var dc = coefficients[base] * quant[0];
        if (scaleShift == 3) {
            plane[offset] = ReducedIdct.dcValue1(dc);
            return;
        }
        var ac = 0;
        for (var k = 1; k < 64; k++) {
            var value = coefficients[base + k] * quant[k];
            block[k] = value;
            ac |= value;
        }
        var size = 8 >> scaleShift;
        if (ac == 0) {
            var value = ReducedIdct.dcValue(dc);
            for (var row = 0; row < size; row++) {
                Arrays.fill(plane, offset + row * stride, offset + row * stride + size, value);
            }
            return;
        }
        block[0] = dc;
        switch (scaleShift) {
            case 0 -> ScalarIdctKernel.INSTANCE.idct8(block, plane, offset, stride);
            case 1 -> ReducedIdct.idct4(block, plane, offset, stride);
            default -> ReducedIdct.idct2(block, plane, offset, stride);
        }
    }

    /**
     * Expands a zigzag-ordered table to the 80-entry form the entropy decoders index.
     *
     * @param table the 64 zigzag-ordered quantizer values
     * @return the padded copy, entries 64 to 79 repeating entry 63
     */
    private static int[] zigzagQuant(int[] table) {
        var result = Arrays.copyOf(table, 80);
        Arrays.fill(result, 64, 80, table[63]);
        return result;
    }

    /**
     * Converts a latched zigzag-ordered table to natural order.
     *
     * @param zigzag the latched table, or {@code null} for a component no scan referenced
     * @return the natural-order table, all zero when {@code zigzag} is {@code null}
     */
    private static int[] naturalQuant(int[] zigzag) {
        var natural = new int[64];
        if (zigzag != null) {
            for (var k = 0; k < 64; k++) {
                natural[JpegTables.ZIGZAG[k]] = zigzag[k];
            }
        }
        return natural;
    }

    /**
     * {@return the frame, or {@code null} before {@link #readHeaders()} succeeded; for a hierarchical image the
     * frame header of the completed image that its DHP segment defines}
     */
    public JpegFrame frame() {
        return image != null ? image : frame;
    }

    /**
     * {@return a copy of the TIFF block of the first Exif APP1 segment, or {@code null} when absent}
     */
    public byte[] exif() {
        return exif == null ? null : exif.clone();
    }

    /**
     * Returns the XMP packet of the stream: the packet of the first XMP APP1 segment, with the
     * properties of the Extended XMP packet it names merged into it when the Extended XMP segments
     * hold the whole extended packet.
     *
     * @param limits the bounds of the extended packet
     * @return a copy of the packet bytes, or {@code null} when absent
     */
    public byte[] xmp(MediaLimits limits) {
        if (xmp == null) {
            return null;
        }
        var merged = JpegXmp.mergeBytes(xmp, extendedXmp, limits);
        return merged == xmp ? xmp.clone() : merged;
    }

    /**
     * {@return the ICC profile reassembled from the APP2 segments, or {@code null} when absent or
     * inconsistent}
     */
    public byte[] iccProfile() {
        return icc.profile();
    }

    /**
     * Returns the density of a JFIF header.
     *
     * @param units the units byte: 0 for an aspect ratio, 1 for inches, 2 for centimeters
     * @param x the horizontal density
     * @param y the vertical density
     * @return the density, or {@code null} for another unit or a zero value
     */
    private static PixelDensity jfifDensity(int units, int x, int y) {
        if (x == 0 || y == 0) {
            return null;
        }
        return switch (units) {
            case 0 -> PixelDensity.ofAspectRatio(x, y);
            case 1 -> PixelDensity.ofDotsPerInch(x, y);
            case 2 -> PixelDensity.ofDotsPerCentimeter(x, y);
            default -> null;
        };
    }

    /**
     * Returns the pixel density the stream declares: that of the first JFIF {@code APP0} segment before the
     * first scan when it is valid and physical, otherwise the resolution of the IFD0 of the first
     * {@code APP1 Exif} segment when it is valid and physical, otherwise the aspect ratio of either, the JFIF one
     * first.
     *
     * @return the density, or {@code null} when neither declares a valid one
     */
    public PixelDensity density() {
        if (jfifDensity != null && jfifDensity.isPhysical()) {
            return jfifDensity;
        }
        var exifDensity = exif == null ? null : TiffExif.density(exif, 0);
        if (exifDensity != null && exifDensity.isPhysical()) {
            return exifDensity;
        }
        return jfifDensity != null ? jfifDensity : exifDensity;
    }

    /**
     * {@return whether a JFIF {@code APP0} segment precedes the first scan}
     */
    public boolean hasJfif() {
        return jfif;
    }

    /**
     * {@return whether an Adobe {@code APP14} segment precedes the first scan, which marks CMYK
     * samples as stored inverted}
     */
    public boolean hasAdobeMarker() {
        return adobeTransform >= 0;
    }
}
