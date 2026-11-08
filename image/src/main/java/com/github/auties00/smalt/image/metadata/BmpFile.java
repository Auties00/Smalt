package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.bmp.internal.BmpHeader;
import com.github.auties00.smalt.internal.edit.EditPlan;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.io.MediaSource;

import java.util.List;
import java.util.Map;

/**
 * Reads and rewrites the ICC profile a V5 header embeds in a BMP file, the only metadata a BMP file
 * carries.
 *
 * <p>A replaced profile is written after the pixel data and referenced with the {@code PROFILE_EMBEDDED}
 * color space type. A file whose header is smaller than V5 gets a V5 header built from its fields (the
 * color masks moved into it, a 3-byte color table widened to 4 bytes); the color table gap and the pixel
 * data are copied unchanged, the old profile left out. Removing the profile declares sRGB and leaves the
 * header in place. OS/2 bitmap arrays, and OS/2 Huffman or RLE24 files, whose compression a V5 header
 * cannot state, are not edited.
 */
final class BmpFile implements ImageFile {
    /**
     * The size of a V5 information header.
     */
    private static final int V5 = 124;

    /**
     * The source.
     */
    private final MediaSource source;

    /**
     * The resource bounds.
     */
    private final MediaLimits limits;

    /**
     * The headers of the file, or of the first bitmap of an array.
     */
    private final BmpHeader header;

    /**
     * Whether the file is an OS/2 bitmap array.
     */
    private final boolean array;

    /**
     * Parses the headers.
     *
     * @param source the BMP file
     * @param limits the resource bounds
     * @throws MediaException.Malformed if the headers are invalid
     * @throws MediaException.Unsupported if the file uses an unknown compression
     */
    BmpFile(MediaSource source, MediaLimits limits) {
        this.source = source;
        this.limits = limits;
        var head = ImageFiles.readAvailable(source, 0, 2);
        this.array = head.length == 2 && head[0] == 'B' && head[1] == 'A';
        this.header = BmpHeader.read(source, array ? BmpHeader.ARRAY_HEADER : 0);
    }

    @Override
    public ImageMetadata metadata() {
        return new ImageMetadata(ImageFormat.BMP, null, null, null, embeddedProfile(), List.of(), Map.of());
    }

    /**
     * {@return the embedded ICC profile, or {@code null} when there is none or it lies outside the file}
     */
    private byte[] embeddedProfile() {
        if (header.colorSpace() != BmpHeader.EMBEDDED || header.iccSize() <= 0
                || header.iccOffset() + header.iccSize() > source.size()) {
            return null;
        }
        return ImageFiles.read(source, header.iccOffset(), header.iccSize(), limits);
    }

    @Override
    public EditPlan plan(Resolved target) {
        if (target.exif() instanceof ImageMetadataEdit.Change.Replace || target.xmp() instanceof ImageMetadataEdit.Change.Replace
                || target.iptc() instanceof ImageMetadataEdit.Change.Replace
                || target.comments() instanceof ImageMetadataEdit.Change.Replace
                || target.text() instanceof ImageMetadataEdit.Change.Replace) {
            throw ImageFiles.unsupported(ImageFormat.BMP, "metadata other than an ICC profile");
        }
        var size = source.size();
        var keep = EditPlan.builder().copy(0, size).build();
        var replacement = target.iccProfile();
        var remove = target.icc() instanceof ImageMetadataEdit.Change.Remove;
        if (replacement == null && (!remove || header.colorSpace() != BmpHeader.EMBEDDED)) {
            return keep;
        }
        if (array) {
            throw ImageFiles.unsupported(ImageFormat.BMP, "a color profile in an OS/2 bitmap array");
        }
        if (header.compression() == BmpHeader.HUFFMAN || header.compression() == BmpHeader.RLE24) {
            throw ImageFiles.unsupported(ImageFormat.BMP, "a color profile with OS/2 Huffman or RLE24 compression");
        }
        if (replacement != null && replacement.length > Integer.MAX_VALUE - 16) {
            throw new MediaException.LimitExceeded("the ICC profile exceeds the largest Java array");
        }
        var infoSize = header.headerSize();
        var entrySize = infoSize == 12 ? 3 : 4;
        var paletteStart = header.infoOffset() + infoSize + separateMasks();
        var paletteEnd = Math.min(size, paletteStart + (long) header.paletteSize() * entrySize);
        var dataOffset = Math.min(size, header.dataOffset());
        var info = infoSize >= 108 ? copyInfo(infoSize) : buildInfo();
        var palette = paletteBytes(paletteStart, entrySize);
        var excludeStart = -1L;
        var excludeEnd = -1L;
        if (header.iccSize() > 0 && (header.colorSpace() == BmpHeader.EMBEDDED || header.colorSpace() == BmpHeader.LINKED)) {
            var profileStart = header.iccOffset();
            var profileEnd = Math.min(size, profileStart + header.iccSize());
            if (profileStart >= paletteEnd && profileStart < profileEnd
                    && (profileEnd <= dataOffset || profileStart >= pixelDataEnd())) {
                excludeStart = profileStart;
                excludeEnd = profileEnd;
            }
        }
        var excluded = excludeStart < 0 ? 0 : excludeEnd - excludeStart;
        var excludedBeforeData = excludeStart >= 0 && excludeEnd <= dataOffset ? excluded : 0;
        var prefix = BmpHeader.FILE_HEADER + info.length + palette.length;
        var copied = size - paletteEnd - excluded;
        var total = prefix + copied + (replacement == null ? 0 : replacement.length);
        if (total > 0xFFFF_FFFFL) {
            throw new MediaException.LimitExceeded("the edited BMP file would exceed 4 GiB");
        }
        var fileHeader = new byte[BmpHeader.FILE_HEADER];
        fileHeader[0] = 'B';
        fileHeader[1] = 'M';
        ByteArrays.put32le(fileHeader, 2, total);
        ByteArrays.put32le(fileHeader, 10, prefix + (dataOffset - paletteEnd) - excludedBeforeData);
        ByteArrays.put32le(info, BmpHeader.COLOR_SPACE_FIELD, replacement != null ? BmpHeader.EMBEDDED : BmpHeader.SRGB);
        ByteArrays.put32le(info, BmpHeader.PROFILE_FIELD, replacement != null ? prefix + copied - BmpHeader.FILE_HEADER : 0);
        ByteArrays.put32le(info, BmpHeader.PROFILE_FIELD + 4, replacement != null ? replacement.length : 0);
        var plan = EditPlan.builder().bytes(fileHeader).bytes(info).bytes(palette);
        if (excludeStart >= 0) {
            plan.copy(paletteEnd, excludeStart - paletteEnd).copy(excludeEnd, size - excludeEnd);
        } else {
            plan.copy(paletteEnd, size - paletteEnd);
        }
        if (replacement != null) {
            plan.bytes(replacement);
        }
        return plan.build();
    }

    /**
     * {@return the file offset where the pixel data certainly ends: after the stated image size, or after
     * the uncompressed rows; the end of the file when neither is known}
     */
    private long pixelDataEnd() {
        var uncompressed = header.compression() == BmpHeader.RGB || header.compression() == BmpHeader.BITFIELDS
                || header.compression() == BmpHeader.CMYK;
        var length = uncompressed ? Math.max(header.dataSize(), header.rowStride() * header.height()) : header.dataSize();
        return length > 0 ? header.dataOffset() + length : source.size();
    }

    /**
     * {@return the number of bytes of color masks stored after the information header}
     */
    private int separateMasks() {
        if (header.compression() != BmpHeader.BITFIELDS || header.headerSize() >= 52) {
            return 0;
        }
        var compression = ByteArrays.i32le(ImageFiles.readAvailable(source, header.infoOffset() + 16, 4), 0);
        return compression == 6 ? 16 : 12;
    }

    /**
     * Copies an information header of at least 108 bytes, widened to a V5 header.
     *
     * @param infoSize the size of the stored header
     * @return the header bytes, at least 124
     */
    private byte[] copyInfo(long infoSize) {
        var stored = ImageFiles.read(source, header.infoOffset(), infoSize, limits);
        if (stored.length >= V5) {
            return stored;
        }
        var info = new byte[V5];
        System.arraycopy(stored, 0, info, 0, stored.length);
        ByteArrays.put32le(info, 0, V5);
        // LCS_GM_IMAGES, the rendering intent of a header that states none
        ByteArrays.put32le(info, 108, 4);
        return info;
    }

    /**
     * Builds a V5 header from the fields of a smaller one.
     *
     * @return the header bytes
     */
    private byte[] buildInfo() {
        var info = new byte[V5];
        ByteArrays.put32le(info, 0, V5);
        ByteArrays.put32le(info, 4, header.width());
        ByteArrays.put32le(info, 8, header.topDown() ? -(long) header.height() : header.height());
        ByteArrays.put16le(info, 12, 1);
        ByteArrays.put16le(info, 14, header.bitCount());
        var compression = switch (header.compression()) {
            case BmpHeader.RLE8 -> 1;
            case BmpHeader.RLE4 -> 2;
            case BmpHeader.BITFIELDS -> 3;
            case BmpHeader.JPEG -> 4;
            case BmpHeader.PNG -> 5;
            case BmpHeader.CMYK -> 11;
            case BmpHeader.CMYK_RLE8 -> 12;
            case BmpHeader.CMYK_RLE4 -> 13;
            default -> 0;
        };
        ByteArrays.put32le(info, 16, compression);
        ByteArrays.put32le(info, 20, header.dataSize());
        if (header.headerSize() >= 32) {
            var stored = ImageFiles.readAvailable(source, header.infoOffset() + 24, 8);
            if (stored.length == 8) {
                System.arraycopy(stored, 0, info, 24, 8);
            }
        }
        ByteArrays.put32le(info, 32, header.bitCount() <= 8 ? header.paletteSize() : 0);
        if (header.compression() == BmpHeader.BITFIELDS) {
            for (var channel = 0; channel < 4; channel++) {
                ByteArrays.put32le(info, 40 + channel * 4, header.masks()[channel]);
            }
        }
        ByteArrays.put32le(info, 108, 4);
        return info;
    }

    /**
     * Reads the color table as 4-byte entries.
     *
     * @param start the file offset of the stored table
     * @param entrySize the stored entry size, 3 or 4
     * @return the table
     */
    private byte[] paletteBytes(long start, int entrySize) {
        var count = header.bitCount() <= 8 ? header.paletteSize() : 0;
        var stored = ImageFiles.read(source, start, (long) count * entrySize, limits);
        if (entrySize == 4) {
            return stored;
        }
        var table = new byte[count * 4];
        for (var i = 0; i < count; i++) {
            System.arraycopy(stored, i * 3, table, i * 4, 3);
        }
        return table;
    }
}
