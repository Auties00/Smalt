/* Ports jxl-oxide 0.12.6 (crates/jxl-jbr: the jbrd header parser). Copyright Wonwoo Choi and the jxl-oxide
   contributors; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.jpeg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlBitReader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Holds the bit-coded header of a {@code jbrd} box (ISO/IEC 18181-2 annex A): everything a JPEG
 * file has beyond its DCT coefficients, except the bulk data (application and comment segments,
 * unrecognized bytes between markers, and the bytes after the end of the image) that follows it
 * Brotli-compressed.
 */
final class JbrdHeader {
    /**
     * The largest number of markers accepted.
     */
    private static final int MAX_MARKERS = 1 << 16;

    /**
     * The largest block index a reset point or zero run may name.
     */
    private static final long MAX_BLOCK_INDEX = 3L << 26;

    /**
     * Whether the JPEG is grayscale.
     */
    final boolean gray;

    /**
     * The marker bytes in file order, each {@code 0xC0} to {@code 0xFF}; {@code 0xFF} stands for
     * unrecognized bytes between markers.
     */
    final int[] markers;

    /**
     * The type of each application segment: 0 stored verbatim, 1 ICC, 2 EXIF, 3 XMP.
     */
    final int[] appTypes;

    /**
     * The length of each application segment, including its type byte and length field.
     */
    final int[] appLengths;

    /**
     * The length of each comment segment, including its length field.
     */
    final int[] comLengths;

    /**
     * The precision of each quantization table: 0 for 8-bit values, 1 for 16-bit values.
     */
    final int[] quantPrecision;

    /**
     * The slot of each quantization table.
     */
    final int[] quantIndex;

    /**
     * Whether each quantization table is the last of its {@code DQT} segment.
     */
    final boolean[] quantLast;

    /**
     * The identifier of each frame component.
     */
    final int[] componentIds;

    /**
     * The quantization table slot of each frame component.
     */
    final int[] componentQuant;

    /**
     * The Huffman tables, in {@code DHT} order.
     */
    final List<HuffmanCode> huffmanCodes;

    /**
     * The scan headers, in {@code SOS} order.
     */
    final List<ScanInfo> scans;

    /**
     * The restart interval, or 0.
     */
    final int restartInterval;

    /**
     * The length of each run of unrecognized bytes between markers.
     */
    final int[] intermarkerLengths;

    /**
     * The number of bytes after the end of the image.
     */
    final int tailLength;

    /**
     * The padding bits of the entropy-coded segments, LSB first, or {@code null} for all ones.
     */
    final byte[] padding;

    /**
     * The number of valid bits in {@link #padding}.
     */
    final int paddingBits;

    /**
     * The offset of the Brotli-compressed data in the box.
     */
    final int dataOffset;

    /**
     * Describes one Huffman table.
     *
     * @param ac whether it codes AC coefficients
     * @param id the table slot, 0 to 3
     * @param last whether it is the last table of its {@code DHT} segment
     * @param counts the number of codes of each length 0 to 16, including the reserved all-ones code
     * @param values the symbols in code order, including a final reserved symbol
     */
    record HuffmanCode(boolean ac, int id, boolean last, int[] counts, int[] values) {
    }

    /**
     * Describes one scan.
     *
     * @param ss the spectral selection start
     * @param se the spectral selection end
     * @param al the successive approximation low bit
     * @param ah the successive approximation high bit
     * @param componentIndex the frame component of each scan component
     * @param acTable the AC table slot of each scan component
     * @param dcTable the DC table slot of each scan component
     * @param resetPoints the blocks before which the end-of-band run is flushed
     * @param extraZeroRuns the number of extra zero-run symbols written before each listed block's
     * end of block
     */
    record ScanInfo(int ss, int se, int al, int ah, int[] componentIndex, int[] acTable, int[] dcTable,
                    Set<Integer> resetPoints, Map<Integer, Integer> extraZeroRuns) {
    }

    /**
     * Parses the header.
     *
     * @param br the reader over the box
     * @throws MediaException.Malformed if the header is invalid or truncated
     */
    JbrdHeader(JxlBitReader br) {
        this.gray = br.readBool();
        var markerList = new ArrayList<Integer>();
        var apps = 0;
        var coms = 0;
        var scanCount = 0;
        var intermarkers = 0;
        var hasDri = false;
        while (markerList.isEmpty() || markerList.getLast() != 0xD9) {
            if (markerList.size() >= MAX_MARKERS) {
                throw malformed("too many JPEG markers");
            }
            var marker = br.readBits(6) + 0xC0;
            if (marker >= 0xE0 && marker <= 0xEF) {
                apps++;
            } else if (marker == 0xFE) {
                coms++;
            } else if (marker == 0xDA) {
                scanCount++;
            } else if (marker == 0xFF) {
                intermarkers++;
            } else if (marker == 0xDD) {
                hasDri = true;
            }
            markerList.add(marker);
        }
        this.markers = markerList.stream().mapToInt(Integer::intValue).toArray();
        this.appTypes = new int[apps];
        this.appLengths = new int[apps];
        for (var index = 0; index < apps; index++) {
            appTypes[index] = br.readU32(0, 0, 1, 0, 2, 1, 4, 2);
            appLengths[index] = br.readBits(16) + 1;
        }
        this.comLengths = new int[coms];
        for (var index = 0; index < coms; index++) {
            comLengths[index] = br.readBits(16) + 1;
        }
        var quantCount = br.readBits(2) + 1;
        this.quantPrecision = new int[quantCount];
        this.quantIndex = new int[quantCount];
        this.quantLast = new boolean[quantCount];
        for (var index = 0; index < quantCount; index++) {
            quantPrecision[index] = br.readBits(1);
            quantIndex[index] = br.readBits(2);
            quantLast[index] = br.readBool();
        }
        this.componentIds = switch (br.readBits(2)) {
            case 0 -> new int[]{1};
            case 1 -> new int[]{1, 2, 3};
            case 2 -> new int[]{'R', 'G', 'B'};
            default -> {
                var ids = new int[br.readBits(2) + 1];
                for (var index = 0; index < ids.length; index++) {
                    ids[index] = br.readBits(8);
                }
                yield ids;
            }
        };
        this.componentQuant = new int[componentIds.length];
        for (var index = 0; index < componentIds.length; index++) {
            componentQuant[index] = br.readBits(2);
        }
        var huffmanCount = br.readU32(4, 0, 2, 3, 10, 4, 26, 6);
        this.huffmanCodes = new ArrayList<>(huffmanCount);
        for (var index = 0; index < huffmanCount; index++) {
            huffmanCodes.add(readHuffmanCode(br));
        }
        var scanHeaders = new int[scanCount][];
        var scanComponents = new int[scanCount][][];
        for (var index = 0; index < scanCount; index++) {
            var count = br.readBits(2) + 1;
            scanHeaders[index] = new int[]{br.readBits(6), br.readBits(6), br.readBits(4), br.readBits(4)};
            var components = new int[3][count];
            for (var c = 0; c < count; c++) {
                components[0][c] = br.readBits(2);
                components[1][c] = br.readBits(2);
                components[2][c] = br.readBits(2);
            }
            scanComponents[index] = components;
            br.readU32(0, 0, 1, 0, 2, 0, 3, 3);
        }
        this.restartInterval = hasDri ? br.readBits(16) : 0;
        this.scans = new ArrayList<>(scanCount);
        for (var index = 0; index < scanCount; index++) {
            var resetPoints = new HashSet<Integer>();
            var resetCount = br.readU32(0, 0, 1, 2, 4, 4, 20, 16);
            var last = -1L;
            for (var point = 0; point < resetCount; point++) {
                var diff = br.readU32(0, 0, 1, 3, 9, 5, 41, 28) & 0xFFFF_FFFFL;
                last = blockIndex(last, diff);
                resetPoints.add((int) last);
            }
            var extraZeroRuns = new HashMap<Integer, Integer>();
            var runCount = br.readU32(0, 0, 1, 2, 4, 4, 20, 16);
            last = -1L;
            for (var run = 0; run < runCount; run++) {
                var count = br.readU32(1, 0, 2, 2, 5, 4, 20, 8);
                var length = br.readU32(0, 0, 1, 3, 9, 5, 41, 28) & 0xFFFF_FFFFL;
                last = blockIndex(last, length);
                extraZeroRuns.put((int) last, count);
            }
            var header = scanHeaders[index];
            var components = scanComponents[index];
            scans.add(new ScanInfo(header[0], header[1], header[2], header[3], components[0], components[1],
                    components[2], resetPoints, extraZeroRuns));
        }
        this.intermarkerLengths = new int[intermarkers];
        for (var index = 0; index < intermarkers; index++) {
            intermarkerLengths[index] = br.readBits(16);
        }
        this.tailLength = br.readU32(0, 0, 1, 8, 257, 16, 65793, 22);
        if (br.readBool()) {
            var bits = br.readBits(24);
            var bytes = new byte[bits / 8 + 1];
            for (var index = 0; index < bits / 8; index++) {
                bytes[index] = (byte) br.readBits(8);
            }
            bytes[bits / 8] = (byte) br.readBits(bits % 8);
            this.padding = bytes;
            this.paddingBits = bits;
        } else {
            this.padding = null;
            this.paddingBits = 0;
        }
        br.zeroPadToByte();
        this.dataOffset = (int) (br.bitPosition() / 8);
    }

    /**
     * Reads one Huffman table.
     *
     * @param br the reader
     * @return the table
     */
    private static HuffmanCode readHuffmanCode(JxlBitReader br) {
        var ac = br.readBool();
        var id = br.readBits(2);
        var last = br.readBool();
        var counts = new int[17];
        var total = 0;
        for (var index = 0; index < 17; index++) {
            counts[index] = br.readU32(0, 0, 1, 0, 2, 3, 0, 8);
            total += counts[index];
        }
        var values = new int[total];
        for (var index = 0; index < total; index++) {
            values[index] = br.readU32(0, 2, 4, 2, 8, 4, 1, 8) & 0xFF;
        }
        return new HuffmanCode(ac, id, last, counts, values);
    }

    /**
     * Advances a delta-coded block index.
     *
     * @param last the previous index, or {@code -1} for the first
     * @param diff the coded difference
     * @return the index
     * @throws MediaException.Malformed if the index is too large
     */
    private static long blockIndex(long last, long diff) {
        var index = last < 0 ? diff : last + diff + 1;
        if (index > MAX_BLOCK_INDEX) {
            throw malformed("JPEG block index too large");
        }
        return index;
    }

    /**
     * Returns the number of bytes of the Brotli-compressed data.
     *
     * @return the total of the verbatim application segments, the comments, the unrecognized
     * bytes and the tail
     */
    long expectedDataLength() {
        var total = (long) tailLength;
        for (var index = 0; index < appTypes.length; index++) {
            if (appTypes[index] == 0) {
                total += appLengths[index];
            }
        }
        for (var length : comLengths) {
            total += length;
        }
        for (var length : intermarkerLengths) {
            total += length;
        }
        return total;
    }

    /**
     * Returns the length of the original ICC profile the {@code APP2} segments held.
     *
     * @return the length, or 0
     */
    long expectedIccLength() {
        var total = 0L;
        for (var index = 0; index < appTypes.length; index++) {
            if (appTypes[index] == 1) {
                total += appLengths[index] - 5 - JpegReconstructor.HEADER_ICC.length;
            }
        }
        return total;
    }

    /**
     * Returns the length of the EXIF payload the first EXIF segment held.
     *
     * @return the length, or 0
     */
    long expectedExifLength() {
        return expectedLength(2, JpegReconstructor.HEADER_EXIF.length);
    }

    /**
     * Returns the length of the XMP packet the first XMP segment held.
     *
     * @return the length, or 0
     */
    long expectedXmpLength() {
        return expectedLength(3, JpegReconstructor.HEADER_XMP.length);
    }

    /**
     * Returns the payload length of the first application segment of a type.
     *
     * @param type the segment type
     * @param header the length of the segment's identifying header
     * @return the length, or 0
     */
    private long expectedLength(int type, int header) {
        for (var index = 0; index < appTypes.length; index++) {
            if (appTypes[index] == type) {
                return appLengths[index] - 3L - header;
            }
        }
        return 0;
    }

    /**
     * Builds the failure of a malformed reconstruction box.
     *
     * @param reason what is wrong with the box, for the message
     * @return the exception
     */
    static MediaException.Malformed malformed(String reason) {
        return new MediaException.Malformed("invalid JPEG XL jbrd box: " + reason);
    }
}
