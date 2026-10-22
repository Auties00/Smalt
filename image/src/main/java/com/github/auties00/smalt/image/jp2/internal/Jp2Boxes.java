/* Ports OpenJPEG 2.5.3 (jp2.c). Copyright (c) 2002-2014, Universite catholique de Louvain (UCL), Belgium, and
   the OpenJPEG authors; BSD 2-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.tiff.TiffExif;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Reads the boxes of a JP2 or JPX file (ISO/IEC 15444-1 annex I and ISO/IEC 15444-2 annex M) that
 * describe the image: the image header, the color specification, the palette, component mapping
 * and channel definitions, the location of the first contiguous codestream, and the EXIF and XMP
 * metadata of {@code uuid} and {@code xml } boxes.
 *
 * <p>A raw codestream (starting with the {@code SOC} and {@code SIZ} markers) is accepted too and
 * yields only the codestream location. Unknown boxes are skipped; of several color
 * specifications the first one with a method this reader understands is used, as I.5.3.3 asks.
 * The methods are enumeration, the restricted and the any ICC methods, and the parameterized color
 * space of JPH files (ISO/IEC 15444-15 D.4.3) for full-range R'G'B' samples of at least three
 * components, which is read as the ICC profile of its primaries and transfer characteristics.
 *
 * @implNote This implementation follows OpenJPEG's {@code jp2.c} in its checks of the palette,
 * component mapping and channel definition boxes ({@code opj_jp2_check_color}).
 */
public final class Jp2Boxes {
    /**
     * The JP2 signature box: its length, type {@code jP  } and content {@code 0D 0A 87 0A}.
     */
    static final byte[] SIGNATURE = {0, 0, 0, 12, 'j', 'P', ' ', ' ', 0x0D, 0x0A, (byte) 0x87, 0x0A};

    /**
     * The UUID of the box that holds an EXIF block, {@code JpgTiffExif->JP2}.
     */
    private static final byte[] EXIF_UUID = {'J', 'p', 'g', 'T', 'i', 'f', 'f', 'E', 'x', 'i', 'f', '-', '>', 'J', 'P', '2'};

    /**
     * The UUID of the box that holds an XMP packet.
     */
    private static final byte[] XMP_UUID = {(byte) 0xBE, 0x7A, (byte) 0xCF, (byte) 0xCB, (byte) 0x97, (byte) 0xA9, 0x42,
            (byte) 0xE8, (byte) 0x9C, 0x71, (byte) 0x99, (byte) 0x94, (byte) 0x91, (byte) 0xE3, (byte) 0xAF, (byte) 0xAC};

    /**
     * The largest number of boxes examined.
     */
    private static final int MAX_BOXES = 1 << 16;

    /**
     * The input bytes.
     */
    private final byte[] data;

    /**
     * The index of the codestream's first byte.
     */
    private int codestreamStart = -1;

    /**
     * The end of the codestream (exclusive).
     */
    private int codestreamEnd;

    /**
     * The color specification method, 0 when none was read.
     */
    private int method;

    /**
     * The number of components the image header declares, 0 before it is read.
     */
    private int headerComponents;

    /**
     * The enumerated color space, or {@code -1}.
     */
    private int enumeratedColorSpace = -1;

    /**
     * The embedded ICC profile, or {@code null}.
     */
    private byte[] iccProfile;

    /**
     * The palette, or {@code null}.
     */
    private Palette palette;

    /**
     * The component mapping: for each channel, the component, mapping type and palette column, or
     * {@code null}.
     */
    private int[][] mapping;

    /**
     * The channel definitions: for each, the channel, type and association, or {@code null}.
     */
    private int[][] definitions;

    /**
     * The EXIF block of a {@code uuid} box, or {@code null}.
     */
    private byte[] exif;

    /**
     * The XMP packet of a {@code uuid} or {@code xml } box, or {@code null}.
     */
    private byte[] xmp;

    /**
     * Holds a palette (I.5.3.4).
     *
     * @param entries the number of entries
     * @param depths the bit depth of each column
     * @param signed whether each column is signed
     * @param values the entries, row-major ({@code entries * columns})
     */
    public record Palette(int entries, int[] depths, boolean[] signed, int[] values) {
        /**
         * Returns the number of columns.
         *
         * @return the column count
         */
        public int columns() {
            return depths.length;
        }
    }

    /**
     * Constructs a reader over the input.
     *
     * @param data the input bytes
     */
    private Jp2Boxes(byte[] data) {
        this.data = data;
    }

    /**
     * Reads the boxes of a JP2 or JPX file, or locates a raw codestream.
     *
     * @param data the input bytes
     * @param length the number of valid bytes
     * @return the boxes read
     * @throws MediaException.Malformed if the input is neither a JP2/JPX file nor a codestream, or
     * a box is invalid
     * @throws MediaException.Unsupported if the file stores its codestream in fragments
     */
    public static Jp2Boxes read(byte[] data, int length) {
        var boxes = new Jp2Boxes(data);
        if (length >= 4 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0x4F && (data[2] & 0xFF) == 0xFF
                && (data[3] & 0xFF) == 0x51) {
            boxes.codestreamStart = 0;
            boxes.codestreamEnd = length;
            return boxes;
        }
        if (length < SIGNATURE.length || !Arrays.equals(data, 0, SIGNATURE.length, SIGNATURE, 0, SIGNATURE.length)) {
            throw new MediaException.Malformed("not a JPEG 2000 file");
        }
        boxes.readTop(SIGNATURE.length, length);
        if (boxes.codestreamStart < 0) {
            throw new MediaException.Malformed("the JPEG 2000 file has no contiguous codestream box");
        }
        boxes.validate();
        return boxes;
    }

    /**
     * Reads the top-level boxes.
     *
     * @param position the index of the first box
     * @param end the end of the input
     */
    private void readTop(int position, int end) {
        var steps = 0;
        var fragments = false;
        while (end - position >= 8 && steps++ < MAX_BOXES) {
            var box = box(position, end);
            switch (box.type) {
                case "jp2h" -> readHeader(box.content, box.end);
                case "jp2c" -> {
                    if (codestreamStart < 0) {
                        codestreamStart = box.content;
                        codestreamEnd = box.end;
                    }
                }
                case "uuid" -> readUuid(box.content, box.end);
                case "xml " -> {
                    if (xmp == null && contains(box.content, box.end, "x:xmpmeta")) {
                        xmp = Arrays.copyOfRange(data, box.content, box.end);
                    }
                }
                case "ftbl" -> fragments = true;
                default -> {
                }
            }
            if (box.end >= end) {
                break;
            }
            position = box.end;
        }
        if (codestreamStart < 0 && fragments) {
            // TODO: read JPX codestreams split into fragment tables (ftbl, flst)
            throw new MediaException.Unsupported("JPEG 2000", "codestreams stored in fragment tables");
        }
    }

    /**
     * Reads the boxes of the JP2 header superbox.
     *
     * @param position the index of its first sub-box
     * @param end its end
     */
    private void readHeader(int position, int end) {
        var steps = 0;
        while (end - position >= 8 && steps++ < MAX_BOXES) {
            var box = box(position, end);
            var content = box.content;
            var size = box.end - content;
            switch (box.type) {
                case "ihdr" -> {
                    if (size < 14) {
                        throw new MediaException.Malformed("truncated JPEG 2000 image header box");
                    }
                    headerComponents = u16(content + 8);
                }
                case "colr" -> readColor(content, size);
                case "pclr" -> {
                    if (palette != null) {
                        throw new MediaException.Malformed("duplicate JPEG 2000 palette box");
                    }
                    readPalette(content, size);
                }
                case "cmap" -> {
                    if (mapping != null) {
                        throw new MediaException.Malformed("duplicate JPEG 2000 component mapping box");
                    }
                    if (size < 4 || size % 4 != 0) {
                        throw new MediaException.Malformed("invalid JPEG 2000 component mapping box");
                    }
                    mapping = new int[size / 4][];
                    for (var i = 0; i < mapping.length; i++) {
                        var at = content + 4 * i;
                        mapping[i] = new int[]{u16(at), data[at + 2] & 0xFF, data[at + 3] & 0xFF};
                    }
                }
                case "cdef" -> {
                    if (definitions != null) {
                        throw new MediaException.Malformed("duplicate JPEG 2000 channel definition box");
                    }
                    if (size < 2) {
                        throw new MediaException.Malformed("truncated JPEG 2000 channel definition box");
                    }
                    var count = u16(content);
                    if (count == 0 || size < 2 + 6 * count) {
                        throw new MediaException.Malformed("invalid JPEG 2000 channel definition box");
                    }
                    definitions = new int[count][];
                    for (var i = 0; i < count; i++) {
                        var at = content + 2 + 6 * i;
                        definitions[i] = new int[]{u16(at), u16(at + 2), u16(at + 4)};
                    }
                }
                default -> {
                }
            }
            if (box.end >= end) {
                break;
            }
            position = box.end;
        }
    }

    /**
     * Reads a color specification box, unless one was already read.
     *
     * @param content the index of its content
     * @param size the content size
     */
    private void readColor(int content, int size) {
        if (method != 0) {
            return;
        }
        if (size < 3) {
            throw new MediaException.Malformed("truncated JPEG 2000 color specification box");
        }
        var meth = data[content] & 0xFF;
        switch (meth) {
            case 1 -> {
                if (size < 7) {
                    throw new MediaException.Malformed("truncated JPEG 2000 color specification box");
                }
                method = 1;
                enumeratedColorSpace = (int) u32(content + 3);
            }
            case 2, 3 -> {
                method = meth;
                iccProfile = Arrays.copyOfRange(data, content + 3, content + size);
            }
            case 5 -> readParameterizedColor(content, size);
            default -> {
                // I.5.3.3: a reader ignores color specifications with other methods
            }
        }
    }

    /**
     * Reads a parameterized color specification (ISO/IEC 15444-15 D.4.3): Rec. ITU-T H.273 code points of the
     * primaries, transfer characteristics and matrix coefficients, and the full range flag. Full-range R'G'B'
     * samples of at least three components become the ICC profile of their primaries and transfer
     * characteristics, or the enumerated sRGB color space; any other specification is ignored, as a method this
     * reader does not understand.
     *
     * @param content the index of the box's content
     * @param size the content size
     * @throws MediaException.Malformed if the box is too short
     */
    private void readParameterizedColor(int content, int size) {
        if (size < 10) {
            throw new MediaException.Malformed("truncated JPEG 2000 color specification box");
        }
        var primariesCode = u16(content + 3);
        var transferCode = u16(content + 5);
        var matrixCode = u16(content + 7);
        var fullRange = (data[content + 9] & 0x80) != 0;
        var primaries = ColorPrimaries.of(primariesCode);
        var transfer = TransferCharacteristics.of(transferCode);
        if (matrixCode != MatrixCoefficients.IDENTITY.code() || !fullRange || headerComponents < 3
                || primaries.code() != primariesCode || transfer.code() != transferCode
                || primaries == ColorPrimaries.UNSPECIFIED || transfer == TransferCharacteristics.UNSPECIFIED) {
            return;
        }
        var profile = ColorProfile.of(new ColorInfo(MatrixCoefficients.IDENTITY, ColorRange.FULL, primaries, transfer));
        method = 5;
        if (profile.isSrgb()) {
            enumeratedColorSpace = 16;
        } else {
            iccProfile = profile.toByteArray();
        }
    }

    /**
     * Reads a palette box.
     *
     * @param content the index of its content
     * @param size the content size
     */
    private void readPalette(int content, int size) {
        if (size < 3) {
            throw new MediaException.Malformed("truncated JPEG 2000 palette box");
        }
        var entries = u16(content);
        var columns = data[content + 2] & 0xFF;
        if (entries == 0 || entries > 1024 || columns == 0) {
            throw new MediaException.Malformed("invalid JPEG 2000 palette size " + entries + "x" + columns);
        }
        if (size < 3 + columns) {
            throw new MediaException.Malformed("truncated JPEG 2000 palette box");
        }
        var depths = new int[columns];
        var signed = new boolean[columns];
        var rowBytes = 0;
        for (var j = 0; j < columns; j++) {
            var b = data[content + 3 + j] & 0xFF;
            depths[j] = (b & 0x7F) + 1;
            signed[j] = (b & 0x80) != 0;
            if (depths[j] > 32) {
                throw new MediaException.Malformed("invalid JPEG 2000 palette depth " + depths[j]);
            }
            rowBytes += (depths[j] + 7) >> 3;
        }
        if ((long) size < 3L + columns + (long) entries * rowBytes) {
            throw new MediaException.Malformed("truncated JPEG 2000 palette entries");
        }
        var values = new int[entries * columns];
        var at = content + 3 + columns;
        for (var i = 0; i < entries; i++) {
            for (var j = 0; j < columns; j++) {
                var bytes = (depths[j] + 7) >> 3;
                var value = 0L;
                for (var k = 0; k < bytes; k++) {
                    value = (value << 8) | (data[at++] & 0xFF);
                }
                if (depths[j] < 32) {
                    value &= (1L << depths[j]) - 1;
                }
                if (signed[j] && (value & (1L << (depths[j] - 1))) != 0) {
                    value -= 1L << depths[j];
                }
                values[i * columns + j] = (int) value;
            }
        }
        palette = new Palette(entries, depths, signed, values);
    }

    /**
     * Reads a {@code uuid} box holding EXIF or XMP metadata.
     *
     * @param content the index of its content
     * @param end its end
     */
    private void readUuid(int content, int end) {
        if (end - content < 16) {
            return;
        }
        if (exif == null && Arrays.equals(data, content, content + 16, EXIF_UUID, 0, 16)) {
            var start = content + 16;
            start += TiffExif.prefixLength(data, start);
            if (end - start >= 8) {
                exif = Arrays.copyOfRange(data, start, end);
            }
        } else if (xmp == null && Arrays.equals(data, content, content + 16, XMP_UUID, 0, 16)) {
            xmp = Arrays.copyOfRange(data, content + 16, end);
        }
    }

    /**
     * Checks the palette and the component mapping against each other; one without the other is
     * ignored, as I.5.3.4 requires both.
     *
     * @throws MediaException.Malformed if a mapping is invalid
     */
    private void validate() {
        if ((palette == null) != (mapping == null)) {
            palette = null;
            mapping = null;
        }
        if (mapping == null) {
            return;
        }
        for (var entry : mapping) {
            var type = entry[1];
            var column = entry[2];
            if (type > 1) {
                throw new MediaException.Malformed("invalid JPEG 2000 component mapping type " + type);
            }
            if (type == 1 && column >= palette.columns()) {
                throw new MediaException.Malformed("invalid JPEG 2000 palette column " + column);
            }
            if (type == 0 && column != 0) {
                throw new MediaException.Malformed("invalid JPEG 2000 direct component mapping");
            }
        }
    }

    /**
     * Describes one box.
     *
     * @param type the four-character type
     * @param content the index of its content
     * @param end its end
     */
    private record Box(String type, int content, int end) {
    }

    /**
     * Reads the header of the box at a position.
     *
     * @param position the index of the box
     * @param end the end of the enclosing box or input
     * @return the box; a codestream box that runs past {@code end} is cut at {@code end}
     * @throws MediaException.Malformed if its length is invalid
     */
    private Box box(int position, int end) {
        var length = u32(position);
        var type = new String(data, position + 4, 4, StandardCharsets.ISO_8859_1);
        var header = 8;
        if (length == 1) {
            if (end - position < 16) {
                throw new MediaException.Malformed("truncated JPEG 2000 box header");
            }
            length = (u32(position + 8) << 32) | u32(position + 12);
            header = 16;
        } else if (length == 0) {
            length = end - position;
        }
        if (length < header) {
            throw new MediaException.Malformed("invalid JPEG 2000 box length " + length);
        }
        var boxEnd = position + length;
        if (boxEnd > end || boxEnd < 0) {
            if (type.equals("jp2c")) {
                boxEnd = end;
            } else {
                throw new MediaException.Malformed("JPEG 2000 box " + type.strip() + " runs past its container");
            }
        }
        return new Box(type, position + header, (int) boxEnd);
    }

    /**
     * Returns whether a byte range contains an ASCII string.
     *
     * @param start the first index
     * @param end the end of the range
     * @param needle the string
     * @return whether it occurs
     */
    private boolean contains(int start, int end, String needle) {
        var bytes = needle.getBytes(StandardCharsets.ISO_8859_1);
        for (var i = start; i + bytes.length <= end; i++) {
            if (Arrays.equals(data, i, i + bytes.length, bytes, 0, bytes.length)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads a big-endian 16-bit value.
     *
     * @param position the index of the first byte
     * @return the value
     */
    private int u16(int position) {
        return ByteArrays.u16be(data, position);
    }

    /**
     * Reads a big-endian 32-bit unsigned value.
     *
     * @param position the index of the first byte
     * @return the value
     */
    private long u32(int position) {
        return ByteArrays.u32be(data, position);
    }

    /**
     * Returns the index of the codestream's first byte.
     *
     * @return the codestream start
     */
    public int codestreamStart() {
        return codestreamStart;
    }

    /**
     * Returns the end of the codestream.
     *
     * @return the codestream end (exclusive)
     */
    public int codestreamEnd() {
        return codestreamEnd;
    }

    /**
     * Returns the enumerated color space.
     *
     * @return the {@code EnumCS} value, or {@code -1} when the color is not specified by
     * enumeration
     */
    public int enumeratedColorSpace() {
        return enumeratedColorSpace;
    }

    /**
     * Returns the embedded ICC profile.
     *
     * @return the profile, or {@code null}
     */
    public byte[] iccProfile() {
        return iccProfile;
    }

    /**
     * Returns the palette.
     *
     * @return the palette, or {@code null} without both a palette and a component mapping
     */
    public Palette palette() {
        return palette;
    }

    /**
     * Returns the component mapping.
     *
     * @return for each channel the component, mapping type and palette column, or {@code null}
     */
    public int[][] mapping() {
        return mapping;
    }

    /**
     * Returns the channel definitions.
     *
     * @return for each definition the channel, type and association, or {@code null}
     */
    public int[][] definitions() {
        return definitions;
    }

    /**
     * Returns the EXIF block.
     *
     * @return the TIFF structure starting with its byte-order mark, or {@code null}
     */
    public byte[] exif() {
        return exif;
    }

    /**
     * Returns the XMP packet.
     *
     * @return the packet, or {@code null}
     */
    public byte[] xmp() {
        return xmp;
    }
}
