package com.github.auties00.smalt.image.bmp.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.io.MediaSource;

/**
 * Holds the validated file and bitmap headers of a BMP file.
 *
 * <p>{@link #read(MediaSource)} accepts the {@code BITMAPCOREHEADER} (12 bytes, OS/2 1.x), the
 * OS/2 2.x header (16 to 64 bytes, of which only the fields present are read), and the
 * {@code BITMAPINFOHEADER} family: 40 bytes, V2 (52, with the color masks), V3 (56, with the
 * alpha mask), V4 (108) and V5 (124, with an embedded ICC profile); larger headers are read as
 * V5.
 *
 * <p>A file whose pixel data offset is zero is read as a packed bitmap: the pixel data follows
 * the color masks and the color table.
 *
 * @param width the image width in pixels, positive
 * @param height the image height in pixels, positive
 * @param topDown whether the first stored row is the top one (negative stored height)
 * @param bitCount the bits per pixel: 1, 2, 4, 8, 16, 24 or 32
 * @param compression the pixel layout: {@link #RGB}, {@link #RLE8}, {@link #RLE4} or
 * {@link #BITFIELDS}
 * @param masks the red, green, blue and alpha masks for 16- and 32-bit pixels (zero where a
 * channel is absent)
 * @param alphaMaskActive whether the alpha mask applies (with the rule that an alpha channel that
 * is zero everywhere is ignored)
 * @param palette the color table as packed little-endian RGBA pixels (alpha 255)
 * @param paletteSize the number of color table entries
 * @param dataOffset the file offset of the pixel data
 * @param iccOffset the file offset of an embedded ICC profile, or {@code -1}
 * @param iccSize the size of the embedded ICC profile in bytes
 */
public record BmpHeader(int width, int height, boolean topDown, int bitCount, int compression, int[] masks,
                 boolean alphaMaskActive, int[] palette, int paletteSize, long dataOffset, long iccOffset, long iccSize) {
    /**
     * Uncompressed pixels ({@code BI_RGB}).
     */
    public static final int RGB = 0;

    /**
     * Run-length encoded 8-bit pixels ({@code BI_RLE8}).
     */
    public static final int RLE8 = 1;

    /**
     * Run-length encoded 4-bit pixels ({@code BI_RLE4}).
     */
    public static final int RLE4 = 2;

    /**
     * Uncompressed pixels with explicit color masks ({@code BI_BITFIELDS} and
     * {@code BI_ALPHABITFIELDS}).
     */
    public static final int BITFIELDS = 3;

    /**
     * The size of the file header.
     */
    private static final int FILE_HEADER = 14;

    /**
     * The {@code bV5CSType} value of an embedded profile, {@code 'MBED'}.
     */
    private static final int PROFILE_EMBEDDED = 0x4D42_4544;

    /**
     * Reads and validates the headers and the color table.
     *
     * @param source the input, read from offset zero
     * @return the header
     * @throws MediaException.Malformed if a header is truncated or holds invalid values
     * @throws MediaException.Unsupported if the file uses embedded JPEG or PNG data, CMYK, OS/2
     * Huffman or RLE24 compression, 64-bit pixels, or an unknown compression
     */
    public static BmpHeader read(MediaSource source) {
        var fixed = new byte[FILE_HEADER + 4];
        if (SourceBytes.readAvailable(source, 0, fixed, 0, fixed.length) < fixed.length) {
            throw new MediaException.Malformed("input is too short to be a BMP file");
        }
        if (fixed[0] != 'B' || fixed[1] != 'M') {
            throw new MediaException.Malformed("missing BMP signature");
        }
        var dataOffset = ByteArrays.i32le(fixed, 10) & 0xFFFF_FFFFL;
        var infoSize = ByteArrays.i32le(fixed, 14) & 0xFFFF_FFFFL;
        if (infoSize < 12 || (infoSize > 12 && infoSize < 16)) {
            throw new MediaException.Malformed("invalid BMP header size " + infoSize);
        }
        var readSize = (int) Math.min(infoSize, 124);
        var info = new byte[124];
        if (SourceBytes.readAvailable(source, FILE_HEADER, info, 0, readSize) < readSize) {
            throw new MediaException.Malformed("BMP header is truncated");
        }
        var core = infoSize == 12;
        var os2 = !core && infoSize <= 64 && infoSize != 40 && infoSize != 52 && infoSize != 56;
        long width;
        long height;
        int bitCount;
        var compression = 0L;
        var colorsUsed = 0L;
        if (core) {
            width = ByteArrays.u16le(info, 4);
            height = ByteArrays.u16le(info, 6);
            bitCount = ByteArrays.u16le(info, 10);
        } else {
            width = ByteArrays.i32le(info, 4);
            height = ByteArrays.i32le(info, 8);
            bitCount = ByteArrays.u16le(info, 14);
            if (infoSize >= 20) {
                compression = ByteArrays.i32le(info, 16) & 0xFFFF_FFFFL;
            }
            if (infoSize >= 36) {
                colorsUsed = ByteArrays.i32le(info, 32) & 0xFFFF_FFFFL;
            }
        }
        var topDown = height < 0;
        if (width <= 0 || height == 0 || height == Integer.MIN_VALUE) {
            throw new MediaException.Malformed("invalid BMP size " + width + "x" + height);
        }
        height = Math.abs(height);
        var type = compression(compression, os2, bitCount);
        switch (bitCount) {
            case 1, 2, 4, 8, 16, 24, 32 -> {
            }
            case 64 -> throw new MediaException.Unsupported("bmp 64-bit", "64-bit BMP pixels are not supported");
            default -> throw new MediaException.Malformed("invalid BMP bit count " + bitCount);
        }
        if ((type == RLE8 && bitCount != 8) || (type == RLE4 && bitCount != 4)
                || (type == BITFIELDS && bitCount != 16 && bitCount != 32)) {
            throw new MediaException.Malformed("BMP compression " + compression + " is invalid with " + bitCount + " bits per pixel");
        }
        if (topDown && (type == RLE8 || type == RLE4)) {
            throw new MediaException.Malformed("run-length encoded BMP cannot be top-down");
        }
        var headerEnd = FILE_HEADER + infoSize;
        var masks = new int[4];
        var alphaActive = false;
        if (type == BITFIELDS) {
            if (infoSize >= 52) {
                for (var channel = 0; channel < 3; channel++) {
                    masks[channel] = ByteArrays.i32le(info, 40 + channel * 4);
                }
                if (infoSize >= 56) {
                    masks[3] = ByteArrays.i32le(info, 52);
                }
            } else {
                var withAlpha = compression == 6;
                var extra = new byte[withAlpha ? 16 : 12];
                if (SourceBytes.readAvailable(source, headerEnd, extra, 0, extra.length) < extra.length) {
                    throw new MediaException.Malformed("BMP color masks are truncated");
                }
                for (var channel = 0; channel < extra.length / 4; channel++) {
                    masks[channel] = ByteArrays.i32le(extra, channel * 4);
                }
                headerEnd += extra.length;
            }
            if (bitCount == 16) {
                for (var channel = 0; channel < 4; channel++) {
                    masks[channel] &= 0xFFFF;
                }
            }
            validateMasks(masks);
            alphaActive = masks[3] != 0;
        } else if (bitCount == 16) {
            masks[0] = 0x7C00;
            masks[1] = 0x03E0;
            masks[2] = 0x001F;
        } else if (bitCount == 32) {
            masks[0] = 0x00FF_0000;
            masks[1] = 0x0000_FF00;
            masks[2] = 0x0000_00FF;
            masks[3] = 0xFF00_0000;
            alphaActive = true;
        }
        var entrySize = core ? 3 : 4;
        var paletteSize = 0;
        var palette = new int[256];
        if (bitCount <= 8) {
            paletteSize = colorsUsed == 0 || colorsUsed > (1L << bitCount) ? 1 << bitCount : (int) colorsUsed;
            if (dataOffset == 0) {
                dataOffset = headerEnd + (long) paletteSize * entrySize;
            } else if (dataOffset < headerEnd) {
                throw new MediaException.Malformed("BMP pixel data offset " + dataOffset + " lies inside the header");
            } else if (headerEnd + (long) paletteSize * entrySize > dataOffset) {
                paletteSize = (int) ((dataOffset - headerEnd) / entrySize);
            }
            var table = new byte[paletteSize * entrySize];
            var read = SourceBytes.readAvailable(source, headerEnd, table, 0, table.length);
            paletteSize = read / entrySize;
            for (var i = 0; i < paletteSize; i++) {
                var offset = i * entrySize;
                palette[i] = (table[offset + 2] & 0xFF) | (table[offset + 1] & 0xFF) << 8 | (table[offset] & 0xFF) << 16
                        | 0xFF00_0000;
            }
        } else if (dataOffset == 0) {
            dataOffset = headerEnd;
        } else if (dataOffset < headerEnd) {
            throw new MediaException.Malformed("BMP pixel data offset " + dataOffset + " lies inside the header");
        }
        var iccOffset = -1L;
        var iccSize = 0L;
        if (infoSize >= 124 && ByteArrays.i32le(info, 56) == PROFILE_EMBEDDED) {
            iccOffset = FILE_HEADER + (ByteArrays.i32le(info, 112) & 0xFFFF_FFFFL);
            iccSize = ByteArrays.i32le(info, 116) & 0xFFFF_FFFFL;
        }
        return new BmpHeader((int) width, (int) height, topDown, bitCount, type, masks, alphaActive, palette, paletteSize,
                dataOffset, iccOffset, iccSize);
    }

    /**
     * Maps a stored compression value to a supported pixel layout.
     *
     * @param compression the stored value
     * @param os2 whether the header is an OS/2 2.x header, where values 3 and 4 differ
     * @param bitCount the bits per pixel
     * @return {@link #RGB}, {@link #RLE8}, {@link #RLE4} or {@link #BITFIELDS}
     * @throws MediaException.Unsupported for embedded JPEG or PNG data, CMYK, OS/2 Huffman or RLE24,
     * and unknown values
     */
    private static int compression(long compression, boolean os2, int bitCount) {
        if (os2 && compression == 3) {
            throw new MediaException.Unsupported("bmp os/2 huffman", "OS/2 Huffman 1D compressed BMP is not supported");
        }
        if (os2 && compression == 4) {
            throw new MediaException.Unsupported("bmp rle24", "OS/2 RLE24 compressed BMP is not supported");
        }
        return switch ((int) compression) {
            case 0 -> RGB;
            case 1 -> RLE8;
            case 2 -> RLE4;
            case 3, 6 -> BITFIELDS;
            case 4 -> throw new MediaException.Unsupported("bmp embedded jpeg", "BMP with embedded JPEG data is not supported");
            case 5 -> throw new MediaException.Unsupported("bmp embedded png", "BMP with embedded PNG data is not supported");
            case 11, 12, 13 -> throw new MediaException.Unsupported("bmp cmyk", "CMYK BMP is not supported");
            default -> throw new MediaException.Unsupported("bmp compression " + compression,
                    "BMP compression " + compression + " is not supported (" + bitCount + " bits per pixel)");
        };
    }

    /**
     * Checks that every color mask is a run of contiguous bits and that no two masks overlap.
     *
     * @param masks the red, green, blue and alpha masks
     * @throws MediaException.Malformed if a mask is not contiguous or two masks overlap
     */
    private static void validateMasks(int[] masks) {
        var seen = 0;
        for (var mask : masks) {
            if (mask == 0) {
                continue;
            }
            var shifted = mask >>> Integer.numberOfTrailingZeros(mask);
            if ((shifted & (shifted + 1)) != 0) {
                throw new MediaException.Malformed("BMP color mask 0x" + Integer.toHexString(mask) + " is not contiguous");
            }
            if ((seen & mask) != 0) {
                throw new MediaException.Malformed("BMP color masks overlap");
            }
            seen |= mask;
        }
    }

    /**
     * {@return the number of bytes of one stored row, padded to 4 bytes}
     */
    public long rowStride() {
        return ((long) width * bitCount + 31) / 32 * 4;
    }

    /**
     * {@return whether the pixels may carry transparency: an active alpha mask, or run-length
     * encoding (whose skipped pixels are transparent)}
     */
    public boolean declaresAlpha() {
        return alphaMaskActive || compression == RLE8 || compression == RLE4;
    }
}
