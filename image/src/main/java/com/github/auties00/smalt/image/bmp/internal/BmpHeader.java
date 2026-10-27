package com.github.auties00.smalt.image.bmp.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.io.MediaSource;

/**
 * Holds the validated file and bitmap headers of a BMP file, or of one bitmap of an OS/2 bitmap array.
 *
 * <p>{@link #read(MediaSource)} accepts the {@code BITMAPCOREHEADER} (12 bytes, OS/2 1.x), the
 * OS/2 2.x header (16 to 64 bytes, of which only the fields present are read), and the
 * {@code BITMAPINFOHEADER} family: 40 bytes, V2 (52, with the color masks), V3 (56, with the
 * alpha mask), V4 (108, with the color space) and V5 (124, with an embedded or linked ICC profile);
 * larger headers are read as V5.
 *
 * <p>A file whose pixel data offset is zero is read as a packed bitmap: the pixel data follows
 * the color masks and the color table. Every offset is a file offset: the pixel data offset of a
 * bitmap in an array counts from the start of the file, its profile offset from the start of its
 * information header.
 *
 * @param width the image width in pixels, positive
 * @param height the image height in pixels, positive
 * @param topDown whether the first stored row is the top one (negative stored height)
 * @param bitCount the bits per pixel: 1, 2, 4, 8, 16, 24, 32 or 64, or 0 for embedded JPEG or PNG data
 * @param compression the pixel layout: {@link #RGB}, {@link #RLE8}, {@link #RLE4}, {@link #BITFIELDS},
 * {@link #HUFFMAN}, {@link #RLE24}, {@link #JPEG}, {@link #PNG}, {@link #CMYK}, {@link #CMYK_RLE8} or
 * {@link #CMYK_RLE4}
 * @param masks the red, green, blue and alpha masks for 16- and 32-bit pixels (zero where a
 * channel is absent)
 * @param alphaMaskActive whether the alpha channel applies (with the rule that an alpha channel that
 * is zero everywhere is ignored)
 * @param palette the color table as packed little-endian RGBA pixels (alpha 255), or for the CMYK
 * layouts as packed little-endian cyan, magenta, yellow and black values
 * @param paletteSize the number of color table entries
 * @param dataOffset the file offset of the pixel data
 * @param dataSize the stored size of the pixel data in bytes, 0 when not stated
 * @param infoOffset the file offset of the information header
 * @param headerSize the size of the information header in bytes
 * @param colorSpace the {@code bV4CSType} value, {@link #SRGB} when the header has none
 * @param endpoints the red, green and blue endpoints of a calibrated color space, X, Y and Z of each in
 * 2.30 fixed point, all zero when the header has none
 * @param gammas the red, green and blue tone response exponents of a calibrated color space, in 16.16
 * fixed point, all zero when the header has none
 * @param iccOffset the file offset of an embedded or linked ICC profile, or {@code -1}
 * @param iccSize the size of the embedded or linked ICC profile in bytes
 * @param horizontalPixelsPerMeter the stored horizontal resolution in pixels per meter, 0 when the header has
 * none
 * @param verticalPixelsPerMeter the stored vertical resolution in pixels per meter, 0 when the header has none
 */
public record BmpHeader(int width, int height, boolean topDown, int bitCount, int compression, int[] masks,
                        boolean alphaMaskActive, int[] palette, int paletteSize, long dataOffset, long dataSize,
                        long infoOffset, long headerSize, int colorSpace, int[] endpoints, int[] gammas,
                        long iccOffset, long iccSize, int horizontalPixelsPerMeter,
                        int verticalPixelsPerMeter) {
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
     * 1-bit pixels coded with the modified Huffman codes of ITU-T T.4, one row per line (OS/2
     * {@code BCA_HUFFMAN1D}).
     */
    public static final int HUFFMAN = 4;

    /**
     * Run-length encoded 24-bit pixels (OS/2 {@code BCA_RLE24}).
     */
    public static final int RLE24 = 5;

    /**
     * An embedded JPEG file ({@code BI_JPEG}).
     */
    public static final int JPEG = 6;

    /**
     * An embedded PNG file ({@code BI_PNG}).
     */
    public static final int PNG = 7;

    /**
     * Uncompressed CMYK pixels or CMYK color table indices ({@code BI_CMYK}).
     */
    public static final int CMYK = 8;

    /**
     * Run-length encoded 8-bit indices into a CMYK color table ({@code BI_CMYKRLE8}).
     */
    public static final int CMYK_RLE8 = 9;

    /**
     * Run-length encoded 4-bit indices into a CMYK color table ({@code BI_CMYKRLE4}).
     */
    public static final int CMYK_RLE4 = 10;

    /**
     * The {@code bV4CSType} of a calibrated color space, {@code LCS_CALIBRATED_RGB}.
     */
    public static final int CALIBRATED = 0;

    /**
     * The {@code bV4CSType} of sRGB, {@code 'sRGB'}.
     */
    public static final int SRGB = 0x7352_4742;

    /**
     * The {@code bV4CSType} of the system color space, {@code 'Win '}.
     */
    public static final int WINDOWS = 0x5769_6E20;

    /**
     * The {@code bV5CSType} of an embedded profile, {@code 'MBED'}.
     */
    public static final int EMBEDDED = 0x4D42_4544;

    /**
     * The {@code bV5CSType} of a profile named by a file path, {@code 'LINK'}.
     */
    public static final int LINKED = 0x4C49_4E4B;

    /**
     * The size of the file header.
     */
    public static final int FILE_HEADER = 14;

    /**
     * The size of the OS/2 bitmap array header that precedes each file header.
     */
    public static final int ARRAY_HEADER = 14;

    /**
     * The offset of the color space type in an information header of at least 108 bytes.
     */
    public static final int COLOR_SPACE_FIELD = 56;

    /**
     * The offset of the profile offset and size in a V5 information header.
     */
    public static final int PROFILE_FIELD = 112;

    /**
     * Reads and validates the headers and the color table of a BMP file.
     *
     * @param source the input, read from offset zero
     * @return the header
     * @throws MediaException.Malformed if a header is truncated or holds invalid values
     * @throws MediaException.Unsupported if the file uses an unknown compression
     */
    public static BmpHeader read(MediaSource source) {
        return read(source, 0);
    }

    /**
     * Reads and validates the headers and the color table of a bitmap whose file header starts at an offset.
     *
     * @param source the input
     * @param start the file offset of the {@code BM} file header
     * @return the header
     * @throws MediaException.Malformed if a header is truncated or holds invalid values
     * @throws MediaException.Unsupported if the file uses an unknown compression
     */
    public static BmpHeader read(MediaSource source, long start) {
        var fixed = new byte[FILE_HEADER + 4];
        if (SourceBytes.readAvailable(source, start, fixed, 0, fixed.length) < fixed.length) {
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
        var infoOffset = start + FILE_HEADER;
        var readSize = (int) Math.min(infoSize, 124);
        var info = new byte[124];
        if (SourceBytes.readAvailable(source, infoOffset, info, 0, readSize) < readSize) {
            throw new MediaException.Malformed("BMP header is truncated");
        }
        var core = infoSize == 12;
        var os2 = !core && infoSize <= 64;
        long width;
        long height;
        int bitCount;
        var compression = 0L;
        var imageSize = 0L;
        var colorsUsed = 0L;
        var horizontalPixelsPerMeter = 0;
        var verticalPixelsPerMeter = 0;
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
            if (infoSize >= 24) {
                imageSize = ByteArrays.i32le(info, 20) & 0xFFFF_FFFFL;
            }
            if (infoSize >= 32) {
                horizontalPixelsPerMeter = ByteArrays.i32le(info, 24);
                verticalPixelsPerMeter = ByteArrays.i32le(info, 28);
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
        checkBitCount(type, compression, bitCount);
        if (topDown && type != RGB && type != BITFIELDS && type != CMYK && type != JPEG && type != PNG) {
            throw new MediaException.Malformed("compressed BMP cannot be top-down");
        }
        var headerEnd = infoOffset + infoSize;
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
        } else if (type == RGB && bitCount == 16) {
            masks[0] = 0x7C00;
            masks[1] = 0x03E0;
            masks[2] = 0x001F;
        } else if (type == RGB && bitCount == 32) {
            masks[0] = 0x00FF_0000;
            masks[1] = 0x0000_FF00;
            masks[2] = 0x0000_00FF;
            masks[3] = 0xFF00_0000;
            alphaActive = true;
        } else if (bitCount == 64) {
            alphaActive = true;
        }
        var entrySize = core ? 3 : 4;
        var paletteSize = 0;
        var palette = new int[256];
        var cmyk = type == CMYK || type == CMYK_RLE8 || type == CMYK_RLE4;
        if (bitCount >= 1 && bitCount <= 8) {
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
            paletteSize = Math.max(0, read) / entrySize;
            for (var i = 0; i < paletteSize; i++) {
                var offset = i * entrySize;
                palette[i] = cmyk
                        ? (table[offset + 3] & 0xFF) | (table[offset + 2] & 0xFF) << 8 | (table[offset + 1] & 0xFF) << 16
                        | (table[offset] & 0xFF) << 24
                        : (table[offset + 2] & 0xFF) | (table[offset + 1] & 0xFF) << 8 | (table[offset] & 0xFF) << 16
                        | 0xFF00_0000;
            }
        } else if (dataOffset == 0) {
            dataOffset = headerEnd;
        } else if (dataOffset < headerEnd) {
            throw new MediaException.Malformed("BMP pixel data offset " + dataOffset + " lies inside the header");
        }
        var colorSpace = SRGB;
        var endpoints = new int[9];
        var gammas = new int[3];
        if (!os2 && !core && infoSize >= 108) {
            colorSpace = ByteArrays.i32le(info, COLOR_SPACE_FIELD);
            for (var i = 0; i < 9; i++) {
                endpoints[i] = ByteArrays.i32le(info, 60 + i * 4);
            }
            for (var i = 0; i < 3; i++) {
                gammas[i] = ByteArrays.i32le(info, 96 + i * 4);
            }
        }
        var iccOffset = -1L;
        var iccSize = 0L;
        if (infoSize >= 124 && (colorSpace == EMBEDDED || colorSpace == LINKED)) {
            iccOffset = infoOffset + (ByteArrays.i32le(info, PROFILE_FIELD) & 0xFFFF_FFFFL);
            iccSize = ByteArrays.i32le(info, PROFILE_FIELD + 4) & 0xFFFF_FFFFL;
        }
        return new BmpHeader((int) width, (int) height, topDown, bitCount, type, masks, alphaActive, palette, paletteSize,
                dataOffset, imageSize, infoOffset, infoSize, colorSpace, endpoints, gammas, iccOffset, iccSize,
                horizontalPixelsPerMeter, verticalPixelsPerMeter);
    }

    /**
     * Maps a stored compression value to a pixel layout.
     *
     * @param compression the stored value
     * @param os2 whether the header may be an OS/2 2.x header, where values 3 and 4 also code
     * Huffman and RLE24 data
     * @param bitCount the bits per pixel, which tells the OS/2 meanings of values 3 and 4 apart
     * @return the layout constant
     * @throws MediaException.Unsupported for an unknown value
     */
    private static int compression(long compression, boolean os2, int bitCount) {
        if (os2 && compression == 3 && bitCount == 1) {
            return HUFFMAN;
        }
        if (os2 && compression == 4 && bitCount == 24) {
            return RLE24;
        }
        return switch ((int) compression) {
            case 0 -> RGB;
            case 1 -> RLE8;
            case 2 -> RLE4;
            case 3, 6 -> BITFIELDS;
            case 4 -> JPEG;
            case 5 -> PNG;
            case 11 -> CMYK;
            case 12 -> CMYK_RLE8;
            case 13 -> CMYK_RLE4;
            default -> throw new MediaException.Unsupported("bmp compression " + compression,
                    "BMP compression " + compression + " is not supported (" + bitCount + " bits per pixel)");
        };
    }

    /**
     * Checks that the bits per pixel suit the layout.
     *
     * @param type the layout constant
     * @param compression the stored compression value, for the message
     * @param bitCount the bits per pixel
     * @throws MediaException.Malformed if the combination is invalid
     */
    private static void checkBitCount(int type, long compression, int bitCount) {
        var valid = switch (type) {
            case RGB -> bitCount == 1 || bitCount == 2 || bitCount == 4 || bitCount == 8 || bitCount == 16
                    || bitCount == 24 || bitCount == 32 || bitCount == 64;
            case RLE8, CMYK_RLE8 -> bitCount == 8;
            case RLE4, CMYK_RLE4 -> bitCount == 4;
            case BITFIELDS -> bitCount == 16 || bitCount == 32;
            case HUFFMAN -> bitCount == 1;
            case RLE24 -> bitCount == 24;
            case CMYK -> bitCount == 1 || bitCount == 2 || bitCount == 4 || bitCount == 8 || bitCount == 32;
            default -> true;
        };
        if (!valid) {
            if (type == RGB) {
                throw new MediaException.Malformed("invalid BMP bit count " + bitCount);
            }
            throw new MediaException.Malformed("BMP compression " + compression + " is invalid with " + bitCount
                    + " bits per pixel");
        }
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
     * {@return whether the stored pixels are CMYK values or CMYK color table indices}
     */
    public boolean isCmyk() {
        return compression == CMYK || compression == CMYK_RLE8 || compression == CMYK_RLE4;
    }

    /**
     * {@return whether the pixels may carry transparency: an active alpha channel, or run-length
     * encoding (whose skipped pixels are transparent)}
     */
    public boolean declaresAlpha() {
        return alphaMaskActive || compression == RLE8 || compression == RLE4 || compression == RLE24
                || compression == CMYK_RLE8 || compression == CMYK_RLE4;
    }

    /**
     * {@return the widest color or alpha mask, in bits; 16 for 64-bit pixels}
     */
    public int widestChannel() {
        if (bitCount == 64) {
            return 16;
        }
        var widest = 0;
        for (var mask : masks) {
            widest = Math.max(widest, Integer.bitCount(mask));
        }
        return widest;
    }
}
