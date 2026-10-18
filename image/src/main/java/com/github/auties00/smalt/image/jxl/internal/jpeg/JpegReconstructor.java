/* Ports jxl-oxide 0.12.6 (crates/jxl-jbr) and libjxl (lib/jxl/jpeg/dec_jpeg_data_writer.cc). Copyright Wonwoo Choi
   and the jxl-oxide contributors, and the JPEG XL Project Authors; MIT License and BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.jpeg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.jxl.internal.JxlCodestream;
import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlBitReader;
import com.github.auties00.smalt.image.jxl.internal.brotli.BrotliDecoder;
import com.github.auties00.smalt.image.jxl.internal.header.FrameHeader;
import com.github.auties00.smalt.image.jxl.internal.vardct.HfPass;
import com.github.auties00.smalt.image.jxl.internal.vardct.VarDctFrame;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Rebuilds the original JPEG file of a JPEG XL file that recompressed one losslessly (ISO/IEC
 * 18181-2 annex A).
 *
 * <p>The quantized DCT coefficients come from the first frame of the codestream, decoded up to its
 * coefficients; the {@code jbrd} box supplies every marker, table and scan layout of the original
 * file, the bytes the codestream does not carry, and the padding bits; the ICC profile, EXIF and
 * XMP come from the codestream and the container. The entropy-coded segments are then written
 * again with the original Huffman tables, which gives back the original file byte for byte.
 */
public final class JpegReconstructor {
    /**
     * The identifying header of an ICC {@code APP2} segment.
     */
    static final byte[] HEADER_ICC = "ICC_PROFILE\0".getBytes(StandardCharsets.ISO_8859_1);

    /**
     * The identifying header of an EXIF {@code APP1} segment.
     */
    static final byte[] HEADER_EXIF = "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1);

    /**
     * The identifying header of an XMP {@code APP1} segment.
     */
    static final byte[] HEADER_XMP = "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.ISO_8859_1);

    /**
     * The fixed-point precision of the integer chroma-from-luma.
     */
    private static final int CFL_FIXED_POINT_BITS = 11;

    /**
     * The chroma-from-luma denominator a recompressed JPEG uses.
     */
    private static final int CFL_DEFAULT_COLOR_FACTOR = 84;

    /**
     * The JPEG zig-zag order, as {@code x | y << 16}.
     */
    private static final int[] ZIGZAG = HfPass.dct8NaturalOrder();

    /**
     * The JPEG XL channel (0 X, 1 Y, 2 B) of each component in Y, Cb, Cr order.
     */
    private static final int[] YCBCR_TO_XYB = {1, 0, 2};

    /**
     * The reconstruction box header.
     */
    private final JbrdHeader header;

    /**
     * The decompressed bulk data.
     */
    private final byte[] data;

    /**
     * The coefficients of the first frame.
     */
    private final VarDctFrame frame;

    /**
     * The first frame header.
     */
    private final FrameHeader frameHeader;

    /**
     * The image width.
     */
    private final int imageWidth;

    /**
     * The image height.
     */
    private final int imageHeight;

    /**
     * The original ICC profile, or an empty array.
     */
    private final byte[] icc;

    /**
     * The EXIF payload, or an empty array.
     */
    private final byte[] exif;

    /**
     * The XMP packet, or an empty array.
     */
    private final byte[] xmp;

    /**
     * The JPEG quantization tables of X, Y and B.
     */
    private final int[][] quantTables;

    /**
     * The DC offset of each component in Y, X, B order.
     */
    private final int[] dcOffset = new int[3];

    /**
     * The JPEG upsampling mode of each component in Y, Cb, Cr order.
     */
    private final int[] upsamplingYcbcr;

    /**
     * The output.
     */
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    /**
     * The largest output accepted.
     */
    private final long maxBytes;

    /**
     * The DC Huffman tables by slot, or {@code null}.
     */
    private final HuffmanTable[] dcTables = new HuffmanTable[4];

    /**
     * The AC Huffman tables by slot, or {@code null}.
     */
    private final HuffmanTable[] acTables = new HuffmanTable[4];

    /**
     * The padding bits, or {@code null} for all ones.
     */
    private final JxlBitReader padding;

    /**
     * Whether the frame is progressive.
     */
    private boolean progressive;

    /**
     * The restart interval in effect, or 0.
     */
    private int restartInterval;

    /**
     * The next application segment.
     */
    private int appIndex;

    /**
     * The next ICC segment number.
     */
    private int iccSegment;

    /**
     * The ICC bytes already written.
     */
    private int iccOffset;

    /**
     * The read position in the verbatim application data.
     */
    private int appData;

    /**
     * The next comment segment.
     */
    private int comIndex;

    /**
     * The read position in the comment data.
     */
    private int comData;

    /**
     * The next run of unrecognized bytes.
     */
    private int intermarkerIndex;

    /**
     * The read position in the unrecognized bytes.
     */
    private int intermarkerData;

    /**
     * The next Huffman table.
     */
    private int huffmanIndex;

    /**
     * The next quantization table.
     */
    private int quantIndex;

    /**
     * The values of the last quantization table written, in zig-zag order.
     */
    private int[] lastQuant;

    /**
     * The next scan.
     */
    private int scanIndex;

    /**
     * Prepares a reconstruction.
     *
     * @param header the reconstruction box header
     * @param data the decompressed bulk data
     * @param frame the coefficients of the first frame
     * @param frameHeader the first frame header
     * @param width the image width
     * @param height the image height
     * @param icc the original ICC profile
     * @param exif the EXIF payload
     * @param xmp the XMP packet
     * @param quantTables the JPEG quantization tables of X, Y and B
     * @param maxBytes the largest output accepted
     */
    private JpegReconstructor(JbrdHeader header, byte[] data, VarDctFrame frame, FrameHeader frameHeader, int width,
                              int height, byte[] icc, byte[] exif, byte[] xmp, int[][] quantTables, long maxBytes) {
        this.header = header;
        this.data = data;
        this.frame = frame;
        this.frameHeader = frameHeader;
        this.imageWidth = width;
        this.imageHeight = height;
        this.icc = icc;
        this.exif = exif;
        this.xmp = xmp;
        this.quantTables = quantTables;
        this.maxBytes = maxBytes;
        this.upsamplingYcbcr = new int[]{frameHeader.jpegUpsampling[1], frameHeader.jpegUpsampling[0],
                frameHeader.jpegUpsampling[2]};
        this.padding = header.padding == null ? null
                : new JxlBitReader(header.padding, 0, header.padding.length);
        if (!frameHeader.doYCbCr) {
            for (var i = 0; i < 3; i++) {
                var q = quantTables[YCBCR_TO_XYB[i]][0];
                if (q == 0) {
                    throw JbrdHeader.malformed("zero DC quantizer");
                }
                dcOffset[i] = (short) (1024 / q);
            }
        }
    }

    /**
     * Rebuilds the JPEG file from the reconstruction box and the codestream coefficients.
     *
     * @param codestream the opened codestream, whose container has a {@code jbrd} box
     * @param limits the resource bounds; the byte limit caps the output
     * @return the JPEG file
     * @throws MediaException.Malformed if the box is invalid or does not match the codestream
     * @throws MediaException.LimitExceeded if the frame or the output exceeds the limits
     */
    public static byte[] reconstruct(JxlCodestream codestream, MediaLimits limits) {
        var box = codestream.container().jbrd();
        var header = new JbrdHeader(new JxlBitReader(box, 0, box.length));
        var expected = header.expectedDataLength();
        var data = BrotliDecoder.decompress(box, header.dataOffset, box.length - header.dataOffset,
                Math.min(expected, limits.maxBytes()));
        if (data.length != expected) {
            throw JbrdHeader.malformed("data length " + data.length + " instead of " + expected);
        }
        var image = codestream.header();
        var icc = header.expectedIccLength() > 0 && codestream.icc() != null ? codestream.icc() : new byte[0];
        var exif = header.expectedExifLength() > 0 && codestream.container().exifBox() != null
                ? codestream.container().exifBox() : new byte[0];
        var xmp = header.expectedXmpLength() > 0 && codestream.container().xmp() != null
                ? codestream.container().xmp() : new byte[0];
        checkLength("ICC profile", header.expectedIccLength(), icc.length);
        checkLength("EXIF", header.expectedExifLength(), exif.length);
        checkLength("XMP", header.expectedXmpLength(), xmp.length);
        if (image.xybEncoded()) {
            throw JbrdHeader.malformed("the image is XYB-encoded");
        }
        var decoded = codestream.decodeFirstFrameCoefficients();
        var frameHeader = decoded.header();
        if (frameHeader.encoding != FrameHeader.VARDCT || !frameHeader.isNormalFrame()
                || !frameHeader.hasFlag(FrameHeader.FLAG_SKIP_ADAPTIVE_LF_SMOOTHING)) {
            throw JbrdHeader.malformed("the first frame is not a recompressed JPEG");
        }
        var frame = decoded.varDct();
        var tables = frame.matrices().jpegTables();
        if (tables == null || tables.length != 3) {
            throw JbrdHeader.malformed("the frame has no JPEG quantization tables");
        }
        for (var table : tables) {
            if (table.length != 64) {
                throw JbrdHeader.malformed("the frame has no JPEG quantization tables");
            }
        }
        var subsampled = false;
        for (var mode : frameHeader.jpegUpsampling) {
            subsampled |= mode != 0;
        }
        var globals = decoded.lfGlobal().varDct();
        if (!subsampled && (globals.colorFactor() != CFL_DEFAULT_COLOR_FACTOR || globals.baseCorrelationX() != 0.0f
                || globals.baseCorrelationB() != 0.0f)) {
            throw JbrdHeader.malformed("the frame uses non-integer chroma from luma");
        }
        var reconstructor = new JpegReconstructor(header, data, frame, frameHeader, image.width(), image.height(), icc,
                exif, xmp, tables, limits.maxBytes());
        if (!header.gray && !subsampled) {
            reconstructor.integerChromaFromLuma();
        }
        return reconstructor.write();
    }

    /**
     * Checks a metadata length against the one the box expects.
     *
     * @param what the metadata, for messages
     * @param expected the expected length, or 0 when the JPEG had none
     * @param actual the available length
     * @throws MediaException.Malformed if the lengths differ
     */
    private static void checkLength(String what, long expected, int actual) {
        if (expected > 0 && expected != actual) {
            throw JbrdHeader.malformed(what + " of " + actual + " bytes instead of " + expected);
        }
    }

    /**
     * Adds the integer chroma-from-luma prediction to the X and B coefficients of every group.
     */
    private void integerChromaFromLuma() {
        var ratios = new int[2][64];
        var y = quantTables[1];
        for (var i = 0; i < 64; i++) {
            if (quantTables[0][i] == 0 || quantTables[2][i] == 0) {
                throw JbrdHeader.malformed("zero quantizer");
            }
            ratios[0][i] = (1 << CFL_FIXED_POINT_BITS) * y[i] / quantTables[0][i];
            ratios[1][i] = (1 << CFL_FIXED_POINT_BITS) * y[i] / quantTables[2][i];
        }
        var perRow = frameHeader.groupsPerRow();
        var gd = frameHeader.groupDim();
        var rounding = 1 << (CFL_FIXED_POINT_BITS - 1);
        for (var group = 0; group < frameHeader.numGroups(); group++) {
            var coefficients = frame.coefficients(group);
            if (coefficients == null) {
                continue;
            }
            var meta = frame.hfMetadata(frameHeader.lfGroupOfGroup(group));
            var baseX = (group % perRow % 8) * gd / 64;
            var baseY = (group / perRow % 8) * gd / 64;
            var width = frame.groupStride(group, 1);
            var luma = coefficients[1];
            var height = width == 0 ? 0 : luma.length / width;
            var factors = new int[][]{meta.xFromY(), meta.bFromY()};
            var targets = new int[][]{coefficients[0], coefficients[2]};
            for (var k = 0; k < 2; k++) {
                var target = targets[k];
                var ratio = ratios[k];
                var factorMap = factors[k];
                for (var row = 0; row < height; row++) {
                    var tileRow = (baseY + row / 64) * meta.cflWidth();
                    var qy = row % 8;
                    for (var col = 0; col < width; col++) {
                        var factor = factorMap[tileRow + baseX + col / 64];
                        var q = ratio[qy + 8 * (col % 8)];
                        var scale = factor * (1 << CFL_FIXED_POINT_BITS) / CFL_DEFAULT_COLOR_FACTOR;
                        var qScale = (q * scale + rounding) >> CFL_FIXED_POINT_BITS;
                        var index = row * width + col;
                        target[index] += (luma[index] * qScale + rounding) >> CFL_FIXED_POINT_BITS;
                    }
                }
            }
        }
    }

    /**
     * Writes the JPEG file.
     *
     * @return the file
     */
    private byte[] write() {
        out.write(0xFF);
        out.write(0xD8);
        for (var marker : header.markers) {
            processMarker(marker);
            if (out.size() > maxBytes) {
                throw new MediaException.LimitExceeded("reconstructed JPEG larger than " + maxBytes + " bytes");
            }
        }
        return out.toByteArray();
    }

    /**
     * Writes one marker segment, or the scan data after a start-of-scan marker.
     *
     * @param marker the marker byte
     */
    private void processMarker(int marker) {
        switch (marker) {
            case 0xC0, 0xC1, 0xC2, 0xC9, 0xCA -> writeFrameHeader(marker);
            case 0xC4 -> writeHuffmanTables();
            case 0xD0, 0xD1, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7 -> {
                out.write(0xFF);
                out.write(marker);
            }
            case 0xD9 -> {
                out.write(0xFF);
                out.write(0xD9);
                out.write(data, data.length - header.tailLength, header.tailLength);
            }
            case 0xDA -> writeScan();
            case 0xDB -> writeQuantTables();
            case 0xDD -> {
                var interval = header.restartInterval;
                out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xDD, 0, 4, (byte) (interval >> 8), (byte) interval});
                if (interval != 0) {
                    restartInterval = interval;
                }
            }
            case 0xE0, 0xE1, 0xE2, 0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA, 0xEB, 0xEC, 0xED, 0xEE, 0xEF ->
                    writeAppSegment();
            case 0xFE -> {
                var length = header.comLengths[comIndex++];
                var start = appDataLength() + comData;
                out.write(0xFF);
                out.write(0xFE);
                out.write(data, start, length);
                comData += length;
            }
            case 0xFF -> {
                var length = header.intermarkerLengths[intermarkerIndex++];
                var start = appDataLength() + comDataLength() + intermarkerData;
                out.write(data, start, length);
                intermarkerData += length;
            }
            default -> throw JbrdHeader.malformed("unknown marker " + Integer.toHexString(marker));
        }
    }

    /**
     * Returns the length of the verbatim application data.
     *
     * @return the length
     */
    private int appDataLength() {
        var total = 0;
        for (var index = 0; index < header.appTypes.length; index++) {
            if (header.appTypes[index] == 0) {
                total += header.appLengths[index];
            }
        }
        return total;
    }

    /**
     * Returns the length of the comment data.
     *
     * @return the length
     */
    private int comDataLength() {
        var total = 0;
        for (var length : header.comLengths) {
            total += length;
        }
        return total;
    }

    /**
     * Writes a start-of-frame segment.
     *
     * @param marker the marker byte
     */
    private void writeFrameHeader(int marker) {
        progressive = marker == 0xC2 || marker == 0xCA;
        var components = header.componentIds.length;
        var length = 8 + components * 3;
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) marker, (byte) (length >> 8), (byte) length, 8,
                (byte) (imageHeight >> 8), (byte) imageHeight, (byte) (imageWidth >> 8), (byte) imageWidth,
                (byte) components});
        for (var index = 0; index < components; index++) {
            var mode = index < 3 ? upsamplingYcbcr[index] : 0;
            var sampling = switch (mode) {
                case 1 -> 0x22;
                case 2 -> 0x21;
                case 3 -> 0x12;
                default -> 0x11;
            };
            out.writeBytes(new byte[]{(byte) header.componentIds[index], (byte) sampling,
                    (byte) header.componentQuant[index]});
        }
    }

    /**
     * Writes a Huffman table segment and installs its tables.
     */
    private void writeHuffmanTables() {
        var codes = header.huffmanCodes;
        var end = huffmanIndex;
        while (end < codes.size() && !codes.get(end).last()) {
            end++;
        }
        if (end >= codes.size()) {
            throw JbrdHeader.malformed("Huffman table segment without its last table");
        }
        var length = 2;
        for (var index = huffmanIndex; index <= end; index++) {
            length += 16 + Math.max(codes.get(index).values().length, 1);
        }
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xC4, (byte) (length >> 8), (byte) length});
        for (var index = huffmanIndex; index <= end; index++) {
            var code = codes.get(index);
            var values = code.values();
            if (values.length == 0) {
                throw JbrdHeader.malformed("empty Huffman table");
            }
            var counts = new byte[17];
            counts[0] = (byte) (code.id() | (code.ac() ? 0x10 : 0));
            for (var bits = 1; bits <= 16; bits++) {
                counts[bits] = (byte) code.counts()[bits];
            }
            for (var bits = 16; bits >= 1; bits--) {
                if (counts[bits] != 0) {
                    counts[bits]--;
                    break;
                }
            }
            out.writeBytes(counts);
            for (var v = 0; v < values.length - 1; v++) {
                out.write(values[v]);
            }
            var table = HuffmanTable.build(code.counts(), values);
            if (code.ac()) {
                acTables[code.id()] = table;
            } else {
                dcTables[code.id()] = table;
            }
        }
        huffmanIndex = end + 1;
    }

    /**
     * Writes a quantization table segment from the frame's JPEG tables.
     */
    private void writeQuantTables() {
        var end = quantIndex;
        while (end < header.quantLast.length && !header.quantLast[end]) {
            end++;
        }
        if (end >= header.quantLast.length) {
            throw JbrdHeader.malformed("quantization table segment without its last table");
        }
        var length = 2;
        for (var index = quantIndex; index <= end; index++) {
            length += 65 + (header.quantPrecision[index] != 0 ? 64 : 0);
        }
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xDB, (byte) (length >> 8), (byte) length});
        for (var index = quantIndex; index <= end; index++) {
            var slot = header.quantIndex[index];
            var channel = -1;
            for (var c = 0; c < header.componentQuant.length; c++) {
                if (header.componentQuant[c] == slot) {
                    channel = c;
                    break;
                }
            }
            if (channel >= 0 && frameHeader.doYCbCr && channel <= 1) {
                channel ^= 1;
            }
            if (channel >= 0 && channel < 3) {
                var table = quantTables[channel];
                lastQuant = new int[64];
                for (var k = 0; k < 64; k++) {
                    var position = ZIGZAG[k];
                    lastQuant[k] = table[(position >>> 16) + 8 * (position & 0xFFFF)] & 0xFFFF;
                }
            }
            if (lastQuant == null) {
                throw JbrdHeader.malformed("quantization table without values");
            }
            var precision = header.quantPrecision[index];
            out.write(slot | (precision << 4));
            for (var k = 0; k < 64; k++) {
                if (precision != 0) {
                    out.write(lastQuant[k] >> 8);
                }
                out.write(lastQuant[k]);
            }
        }
        quantIndex = end + 1;
    }

    /**
     * Writes an application segment.
     */
    private void writeAppSegment() {
        if (appIndex >= header.appTypes.length) {
            throw JbrdHeader.malformed("missing application segment");
        }
        var type = header.appTypes[appIndex];
        var length = header.appLengths[appIndex];
        appIndex++;
        var encoded = length - 1;
        switch (type) {
            case 0 -> {
                out.write(0xFF);
                out.write(data, appData, length);
                appData += length;
            }
            case 1 -> {
                out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xE2, (byte) (encoded >> 8), (byte) encoded});
                out.writeBytes(HEADER_ICC);
                var total = 0;
                for (var t : header.appTypes) {
                    total += t == 1 ? 1 : 0;
                }
                out.write(++iccSegment);
                out.write(total);
                var size = length - 5 - HEADER_ICC.length;
                if (size < 0 || iccOffset + size > icc.length) {
                    throw JbrdHeader.malformed("ICC segment beyond the profile");
                }
                out.write(icc, iccOffset, size);
                iccOffset += size;
            }
            case 2 -> {
                out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xE1, (byte) (encoded >> 8), (byte) encoded});
                out.writeBytes(HEADER_EXIF);
                out.writeBytes(exif);
            }
            case 3 -> {
                out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xE1, (byte) (encoded >> 8), (byte) encoded});
                out.writeBytes(HEADER_XMP);
                out.writeBytes(xmp);
            }
            default -> throw JbrdHeader.malformed("invalid application segment type " + type);
        }
    }

    /**
     * Writes a start-of-scan segment and its entropy-coded data.
     */
    private void writeScan() {
        if (scanIndex >= header.scans.size()) {
            throw JbrdHeader.malformed("missing scan");
        }
        var scan = header.scans.get(scanIndex++);
        var count = scan.componentIndex().length;
        var length = 6 + 2 * count;
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xDA, (byte) (length >> 8), (byte) length, (byte) count});
        for (var c = 0; c < count; c++) {
            var component = scan.componentIndex()[c];
            if (component >= header.componentIds.length || component >= 3) {
                throw JbrdHeader.malformed("scan component out of range");
            }
            out.write(header.componentIds[component]);
            out.write((scan.dcTable()[c] << 4) | scan.acTable()[c]);
        }
        out.writeBytes(new byte[]{(byte) scan.ss(), (byte) scan.se(), (byte) ((scan.ah() << 4) | scan.al())});
        if (!progressive && (scan.ss() != 0 || scan.se() != 63 || scan.al() != 0 || scan.ah() != 0)) {
            throw JbrdHeader.malformed("progressive parameters in a sequential scan");
        }
        var writer = new JpegScanWriter(this, scan, progressive ? (scan.ah() == 0 ? 1 : 2) : 0);
        writer.write();
    }

    /**
     * Returns the JPEG upsampling mode of a component.
     *
     * @param component the component in Y, Cb, Cr order
     * @return the mode, 0 to 3
     */
    int upsampling(int component) {
        return upsamplingYcbcr[component];
    }

    /**
     * Returns the number of frame components with a JPEG XL channel.
     *
     * @return 1 to 3
     */
    int componentCount() {
        return Math.min(header.componentIds.length, 3);
    }

    /**
     * Returns the first frame header.
     *
     * @return the header
     */
    FrameHeader frameHeader() {
        return frameHeader;
    }

    /**
     * Returns the coefficients of the first frame.
     *
     * @return the VarDCT data
     */
    VarDctFrame frame() {
        return frame;
    }

    /**
     * Returns the DC offset of a component.
     *
     * @param index the component in Y, X, B order
     * @return the offset
     */
    int dcOffset(int index) {
        return dcOffset[index];
    }

    /**
     * Returns the DC Huffman table in a slot.
     *
     * @param slot the table slot, from 0 to 3
     * @return the table, or {@link HuffmanTable#EMPTY}
     */
    HuffmanTable dcTable(int slot) {
        return dcTables[slot] == null ? HuffmanTable.EMPTY : dcTables[slot];
    }

    /**
     * Returns the AC Huffman table in a slot.
     *
     * @param slot the table slot, from 0 to 3
     * @return the table, or {@link HuffmanTable#EMPTY}
     */
    HuffmanTable acTable(int slot) {
        return acTables[slot] == null ? HuffmanTable.EMPTY : acTables[slot];
    }

    /**
     * Returns the restart interval in effect.
     *
     * @return the interval in MCUs, or 0
     */
    int restartInterval() {
        return restartInterval;
    }

    /**
     * Returns the JPEG zig-zag order.
     *
     * @return the positions, as {@code x | y << 16}
     */
    static int[] zigzag() {
        return ZIGZAG;
    }

    /**
     * Returns the JPEG XL channel of a component in Y, X, B order.
     *
     * @param index the component index
     * @return 0 for X, 1 for Y, 2 for B
     */
    static int xybChannel(int index) {
        return YCBCR_TO_XYB[index];
    }

    /**
     * Reads padding bits for the end of an entropy-coded segment.
     *
     * @param count the number of bits, 1 to 7
     * @return the bits
     * @throws MediaException.Malformed if the stored padding bits run out
     */
    int paddingBits(int count) {
        if (padding == null) {
            return -1;
        }
        if (padding.bitPosition() + count > header.padding.length * 8L) {
            throw JbrdHeader.malformed("padding bits exhausted");
        }
        return padding.readBits(count);
    }

    /**
     * Appends entropy-coded bytes to the output.
     *
     * @param bytes the entropy-coded bytes
     */
    void emit(byte[] bytes) {
        out.writeBytes(bytes);
        if (out.size() > maxBytes) {
            throw new MediaException.LimitExceeded("reconstructed JPEG larger than " + maxBytes + " bytes");
        }
    }

    /**
     * Appends a restart marker to the output.
     *
     * @param index the marker number, 0 to 7
     */
    void emitRestart(int index) {
        out.write(0xFF);
        out.write(0xD0 + index);
    }
}
