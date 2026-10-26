package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifReader;
import com.github.auties00.smalt.image.exif.ExifValue;
import com.github.auties00.smalt.image.exif.ExifWriter;
import com.github.auties00.smalt.internal.edit.EditPlan;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.tiff.TiffHeader;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.XmpPacket;

import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Opens the metadata of a camera raw file other than DNG.
 *
 * <p>The formats with a TIFF signature (Canon CR2, Nikon NEF and NRW, Sony ARW, SR2 and SRF, Pentax
 * PEF, Samsung SRW) are read and edited like TIFF files; the Olympus ORF and Panasonic RW2 files,
 * whose TIFF structure starts with a signature of their own, are read like TIFF files. The others
 * are read here, and cannot be edited:
 * <ul>
 *   <li>Fujifilm RAF: the EXIF block and XMP packet of the JPEG preview the header points at
 *       (big-endian offset and length at bytes 84 and 88);</li>
 *   <li>Canon CR3: the TIFF structures of the {@code CMT1} (first directory), {@code CMT2}
 *       (Exif directory) and {@code CMT4} (GPS directory) boxes of the Canon {@code uuid} box in
 *       {@code moov}, combined into one EXIF block (the Canon maker note of {@code CMT3}, whose
 *       offsets are relative to its own structure, is left out), and the XMP packet of the
 *       top-level XMP {@code uuid} box;</li>
 *   <li>Minolta MRW: the TIFF structure of the {@code TTW} block, read like a TIFF file.</li>
 * </ul>
 * Canon CRW and Sigma X3F files hold no EXIF block and are not read.
 */
final class RawImageFile implements ImageFile {
    /**
     * The user type of the Canon metadata box of a CR3 file.
     */
    private static final byte[] CANON_UUID = {(byte) 0x85, (byte) 0xC0, (byte) 0xB6, (byte) 0x87, (byte) 0x82, 0x0F, 0x11,
            (byte) 0xE0, (byte) 0x81, 0x11, (byte) 0xF4, (byte) 0xCE, 0x46, 0x2B, 0x6A, 0x48};

    /**
     * The user type of the XMP box of an ISO-BMFF file (XMP Specification Part 3).
     */
    private static final byte[] XMP_UUID = {(byte) 0xBE, 0x7A, (byte) 0xCF, (byte) 0xCB, (byte) 0x97, (byte) 0xA9, 0x42,
            (byte) 0xE8, (byte) 0x9C, 0x71, (byte) 0x99, (byte) 0x94, (byte) 0x91, (byte) 0xE3, (byte) 0xAF, (byte) 0xAC};

    /**
     * The largest number of boxes or blocks read.
     */
    private static final int MAX_BOXES = 1 << 12;

    /**
     * The largest box nesting followed in a CR3 file.
     */
    private static final int MAX_DEPTH = 8;

    /**
     * The source.
     */
    private final MediaSource source;

    /**
     * The EXIF block, or {@code null}.
     */
    private final byte[] exif;

    /**
     * The XMP packet, or {@code null}.
     */
    private final XmpPacket xmp;

    /**
     * Holds metadata already read.
     *
     * @param source the raw file
     * @param exif the EXIF block, or {@code null}
     * @param xmp the XMP packet, or {@code null}
     */
    private RawImageFile(MediaSource source, byte[] exif, XmpPacket xmp) {
        this.source = source;
        this.exif = exif;
        this.xmp = xmp;
    }

    /**
     * Opens a camera raw file other than DNG.
     *
     * @param source the file
     * @param limits the resource bounds
     * @param header the leading bytes of the file
     * @return the file
     * @throws MediaException.Unsupported if the raw format holds no readable metadata
     * @throws MediaException.Malformed if the structure is corrupt
     */
    static ImageFile open(MediaSource source, MediaLimits limits, byte[] header) {
        if (header.length >= 8 && ((header[0] == 'I' && header[1] == 'I') || (header[0] == 'M' && header[1] == 'M'))) {
            var little = header[0] == 'I';
            var version = ByteArrays.u16(header, 2, little);
            if (version == 42 || version == 43) {
                return new TiffFile(source, limits, ImageFormat.RAW, null);
            }
            if (version == 0x4F52 || version == 0x5352 || version == 0x0055) {
                // Olympus (IIRO, IIRS, MMOR) and Panasonic (IIU) signatures stand for the TIFF version
                return new TiffFile(source, limits, ImageFormat.RAW, new TiffHeader(little, false, ByteArrays.u32(header, 4, little)));
            }
        }
        if (startsWith(header, 0, "FUJIFILMCCD-RAW")) {
            return raf(source, limits);
        }
        if (header.length >= 12 && startsWith(header, 4, "ftypcrx ")) {
            return cr3(source, limits);
        }
        if (header.length >= 4 && header[0] == 0 && startsWith(header, 1, "MRM")) {
            return mrw(source, limits);
        }
        throw new MediaException.Unsupported("raw", "the metadata of this camera raw format cannot be read");
    }

    /**
     * Builds the failure of an edit of a raw file.
     *
     * @return the exception
     */
    static MediaException.Unsupported notEditable() {
        return new MediaException.Unsupported("raw", "the metadata of this camera raw format cannot be edited");
    }

    /**
     * Reads a Fujifilm RAF file.
     *
     * @param source the file
     * @param limits the resource bounds
     * @return the file
     * @throws MediaException.Malformed if the header or the JPEG preview is corrupt
     */
    private static RawImageFile raf(MediaSource source, MediaLimits limits) {
        var header = ImageFiles.read(source, 0, 92, limits);
        var offset = ByteArrays.u32be(header, 84);
        var length = ByteArrays.u32be(header, 88);
        if (length == 0) {
            return new RawImageFile(source, null, null);
        }
        if (offset < 92 || offset + length > source.size()) {
            throw new MediaException.Malformed("RAF JPEG preview " + offset + "+" + length + " lies outside the "
                    + source.size() + " byte file");
        }
        var metadata = new JpegFile(new Window(source, offset, length), limits).metadata();
        return new RawImageFile(source, metadata.exifBytes().orElse(null), metadata.xmp().orElse(null));
    }

    /**
     * Reads a Minolta MRW file.
     *
     * @param source the file
     * @param limits the resource bounds
     * @return the file
     * @throws MediaException.Malformed if the block structure or the TIFF structure is corrupt
     */
    private static RawImageFile mrw(MediaSource source, MediaLimits limits) {
        var head = ImageFiles.read(source, 0, 8, limits);
        var end = Math.min(source.size(), 8 + ByteArrays.u32be(head, 4));
        var position = 8L;
        for (var count = 0; position + 8 <= end && count < MAX_BOXES; count++) {
            var block = ImageFiles.read(source, position, 8, limits);
            var length = ByteArrays.u32be(block, 4);
            if (position + 8 + length > end) {
                throw new MediaException.Malformed("MRW block of " + length + " bytes runs past its container");
            }
            if (block[0] == 0 && block[1] == 'T' && block[2] == 'T' && block[3] == 'W') {
                var metadata = new TiffFile(new Window(source, position + 8, length), limits).metadata();
                return new RawImageFile(source, metadata.exifBytes().orElse(null), metadata.xmp().orElse(null));
            }
            position += 8 + length;
        }
        return new RawImageFile(source, null, null);
    }

    /**
     * Reads a Canon CR3 file.
     *
     * @param source the file
     * @param limits the resource bounds
     * @return the file
     * @throws MediaException.Malformed if the box structure is corrupt
     */
    private static RawImageFile cr3(MediaSource source, MediaLimits limits) {
        var found = new byte[5][];
        boxes(source, limits, 0, source.size(), 0, found);
        XmpPacket xmp = null;
        if (found[4] != null) {
            try {
                xmp = XmpPacket.parse(found[4]);
            } catch (MediaException.Malformed ignored) {
                // a packet that does not parse is left out
            }
        }
        var ifd0 = directory(found[0]);
        var exif = directory(found[1]);
        var gps = directory(found[3]);
        byte[] block = null;
        if (!ifd0.isEmpty() || !exif.isEmpty() || !gps.isEmpty()) {
            var order = found[0] != null && found[0].length > 0 && found[0][0] == 'I' ? ByteOrder.LITTLE_ENDIAN
                    : ByteOrder.BIG_ENDIAN;
            block = ExifWriter.write(new ExifData(ifd0, exif, gps, Map.of(), Map.of(), Optional.empty()), order);
        }
        return new RawImageFile(source, block, xmp);
    }

    /**
     * Returns the first directory of a CR3 metadata box.
     *
     * @param tiff the TIFF structure of the box, or {@code null}
     * @return the entries, without the directory pointers, empty when the box is absent or unreadable
     */
    private static Map<Integer, ExifValue> directory(byte[] tiff) {
        if (tiff == null) {
            return Map.of();
        }
        try {
            var entries = new LinkedHashMap<>(ExifReader.parse(tiff).ifd0());
            entries.remove(ExifData.EXIF_IFD_POINTER);
            entries.remove(ExifData.GPS_IFD_POINTER);
            entries.remove(ExifData.INTEROP_IFD_POINTER);
            return entries;
        } catch (MediaException.Malformed exception) {
            return Map.of();
        }
    }

    /**
     * Finds the metadata boxes of a CR3 file within a range.
     *
     * @param source the file
     * @param limits the resource bounds
     * @param start the offset of the first box
     * @param end the end of the range
     * @param depth the nesting depth
     * @param found the payloads found: {@code CMT1} to {@code CMT4} at indices 0 to 3 and the XMP
     * packet at index 4, filled in
     */
    private static void boxes(MediaSource source, MediaLimits limits, long start, long end, int depth, byte[][] found) {
        var position = start;
        for (var count = 0; end - position >= 8; count++) {
            if (count >= MAX_BOXES) {
                throw new MediaException.Malformed("more than " + MAX_BOXES + " CR3 boxes");
            }
            var header = ImageFiles.readAvailable(source, position, 16);
            if (header.length < 8) {
                throw new MediaException.Malformed("truncated CR3 box header");
            }
            long size = ByteArrays.u32be(header, 0);
            var headerSize = 8;
            if (size == 1) {
                if (header.length < 16) {
                    throw new MediaException.Malformed("truncated CR3 box header");
                }
                size = ByteArrays.i64be(header, 8);
                headerSize = 16;
            } else if (size == 0) {
                size = end - position;
            }
            if (size < headerSize || size > end - position) {
                throw new MediaException.Malformed("invalid CR3 box size " + size + " at offset " + position);
            }
            var type = new String(header, 4, 4, StandardCharsets.ISO_8859_1);
            var payload = position + headerSize;
            var payloadEnd = position + size;
            switch (type) {
                case "moov" -> {
                    if (depth < MAX_DEPTH) {
                        boxes(source, limits, payload, payloadEnd, depth + 1, found);
                    }
                }
                case "uuid" -> {
                    if (payloadEnd - payload >= 16) {
                        var user = ImageFiles.read(source, payload, 16, limits);
                        if (Arrays.equals(user, CANON_UUID) && depth < MAX_DEPTH) {
                            boxes(source, limits, payload + 16, payloadEnd, depth + 1, found);
                        } else if (Arrays.equals(user, XMP_UUID) && found[4] == null) {
                            found[4] = ImageFiles.read(source, payload + 16, payloadEnd - payload - 16, limits);
                        }
                    }
                }
                case "CMT1", "CMT2", "CMT3", "CMT4" -> {
                    var index = type.charAt(3) - '1';
                    if (found[index] == null && index != 2) {
                        found[index] = ImageFiles.read(source, payload, payloadEnd - payload, limits);
                    }
                }
                default -> {
                }
            }
            position = payloadEnd;
        }
    }

    /**
     * Returns whether bytes hold ASCII text at an offset.
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @param text the expected characters, each matched against one byte
     * @return {@code true} when the bytes match
     */
    private static boolean startsWith(byte[] data, int offset, String text) {
        if (data.length < offset + text.length()) {
            return false;
        }
        for (var i = 0; i < text.length(); i++) {
            if (data[offset + i] != text.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ImageMetadata metadata() {
        return new ImageMetadata(ImageFormat.RAW, exif, xmp, null, null, List.of(), Map.of());
    }

    @Override
    public EditPlan plan(Resolved target) {
        if (!target.keepsAll()) {
            throw notEditable();
        }
        return EditPlan.builder().copy(0, source.size()).build();
    }

    /**
     * Presents a range of a source as a source of its own.
     *
     * @param source the underlying source
     * @param start the offset of the range
     * @param size the length of the range
     */
    private record Window(MediaSource source, long start, long size) implements MediaSource {
        @Override
        public int read(long position, byte[] buffer, int offset, int length) {
            if (length == 0) {
                return 0;
            }
            if (position >= size) {
                return -1;
            }
            return source.read(start + position, buffer, offset, (int) Math.min(length, size - position));
        }

        @Override
        public void close() {
        }
    }
}
