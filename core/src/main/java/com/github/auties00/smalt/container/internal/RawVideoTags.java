package com.github.auties00.smalt.container.internal;

import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.video.RawPixelFormat;
import com.github.auties00.smalt.video.RawVideoFormat;

import java.nio.charset.StandardCharsets;

/**
 * Maps the identifiers containers give uncompressed video to {@link RawVideoFormat}s: FourCCs (AVI compression
 * codes, Matroska {@code ColourSpace}), Windows {@code BITMAPINFOHEADER} bitmaps and QuickTime sample entries.
 */
public final class RawVideoTags {
    /**
     * The {@code BI_RGB} compression of a {@code BITMAPINFOHEADER}.
     */
    private static final int BI_RGB = 0;

    /**
     * The {@code BI_BITFIELDS} compression of a {@code BITMAPINFOHEADER}.
     */
    private static final int BI_BITFIELDS = 3;

    private RawVideoTags() {
        throw new AssertionError();
    }

    /**
     * Maps the FourCC of a planar or packed YUV or RGB frame layout; frames of these layouts are stored top to
     * bottom with rows one after the other.
     *
     * @param fourCc the four bytes, case-sensitive
     * @return the format, or {@code null} for a FourCC of no uncompressed layout
     */
    public static RawVideoFormat fourCc(String fourCc) {
        var pixelFormat = switch (fourCc) {
            case "I420", "IYUV", "i420" -> RawPixelFormat.YUV420P;
            case "YV12", "yv12" -> RawPixelFormat.YVU420P;
            case "UYVY", "2vuy", "UYNV", "HDYC" -> RawPixelFormat.UYVY422;
            case "YUY2", "YUYV", "YUNV", "V422", "yuvs" -> RawPixelFormat.YUYV422;
            case "YVYU" -> RawPixelFormat.YVYU422;
            case "Y800", "Y8  ", "GREY" -> RawPixelFormat.GRAY;
            case "NV12" -> RawPixelFormat.NV12;
            case "NV21" -> RawPixelFormat.NV21;
            case "Y42B" -> RawPixelFormat.YUV422P;
            case "Y41B" -> RawPixelFormat.YUV411P;
            case "444P" -> RawPixelFormat.YUV444P;
            case "RGBA" -> RawPixelFormat.RGBA;
            case "BGRA" -> RawPixelFormat.BGRA;
            case "ABGR" -> RawPixelFormat.ABGR;
            case "ARGB" -> RawPixelFormat.ARGB;
            case "v210" -> RawPixelFormat.V210;
            default -> null;
        };
        return pixelFormat == null ? null : RawVideoFormat.of(pixelFormat);
    }

    /**
     * Maps a FourCC stored as four bytes.
     *
     * @param data the bytes
     * @param offset the index of the FourCC
     * @return the format, or {@code null}
     */
    public static RawVideoFormat fourCc(byte[] data, int offset) {
        if (offset < 0 || offset + 4 > data.length) {
            return null;
        }
        return fourCc(new String(data, offset, 4, StandardCharsets.ISO_8859_1));
    }

    /**
     * Maps a {@code BITMAPINFOHEADER} of an uncompressed bitmap ({@code BI_RGB} at 8, 16, 24 or 32 bits, or
     * {@code BI_BITFIELDS} at 16 bits with 5-5-5 or 5-6-5 masks) or of a FourCC layout.
     *
     * <p>Bitmaps store their rows bottom to top unless {@code biHeight} is negative, each row padded to a multiple of
     * 4 bytes; 32-bit pixels are blue, green, red, alpha; an 8-bit bitmap takes its palette from the {@code RGBQUAD}
     * entries after the header ({@code biClrUsed} of them, or 256 when zero), opaque.
     *
     * @param header the header and what follows it
     * @return the format, or {@code null} when the header describes no uncompressed frame
     */
    public static RawVideoFormat bitmap(byte[] header) {
        if (header == null || header.length < 40) {
            return null;
        }
        var headerSize = (int) Math.min(ByteArrays.u32le(header, 0), header.length);
        var height = ByteArrays.i32le(header, 8);
        var bits = ByteArrays.u16le(header, 14);
        var compression = (int) ByteArrays.u32le(header, 16);
        if (compression != BI_RGB && compression != BI_BITFIELDS) {
            return fourCc(header, 16);
        }
        RawPixelFormat pixelFormat;
        if (compression == BI_BITFIELDS) {
            if (bits != 16 || header.length < 52) {
                return null;
            }
            var maskOffset = headerSize >= 52 ? 40 : Math.min(headerSize, header.length - 12);
            var red = ByteArrays.u32le(header, maskOffset);
            var green = ByteArrays.u32le(header, maskOffset + 4);
            var blue = ByteArrays.u32le(header, maskOffset + 8);
            if (red == 0xF800 && green == 0x07E0 && blue == 0x001F) {
                pixelFormat = RawPixelFormat.RGB565LE;
            } else if (red == 0x7C00 && green == 0x03E0 && blue == 0x001F) {
                pixelFormat = RawPixelFormat.RGB555LE;
            } else {
                return null;
            }
        } else {
            pixelFormat = switch (bits) {
                case 8 -> RawPixelFormat.PAL8;
                case 16 -> RawPixelFormat.RGB555LE;
                case 24 -> RawPixelFormat.BGR24;
                case 32 -> RawPixelFormat.BGRA;
                default -> null;
            };
            if (pixelFormat == null) {
                return null;
            }
        }
        var format = RawVideoFormat.of(pixelFormat).withRowAlignment(4).withBottomUp(height > 0);
        if (pixelFormat == RawPixelFormat.PAL8) {
            var used = (int) Math.min(256, ByteArrays.u32le(header, 32));
            var entries = used == 0 ? 256 : used;
            entries = Math.min(entries, (header.length - headerSize) / 4);
            if (entries <= 0) {
                return null;
            }
            var palette = new int[entries];
            for (var i = 0; i < entries; i++) {
                var at = headerSize + 4 * i;
                palette[i] = 0xFF000000 | (header[at + 2] & 0xFF) << 16 | (header[at + 1] & 0xFF) << 8
                        | (header[at] & 0xFF);
            }
            format = format.withPalette(palette);
        }
        return format;
    }

    /**
     * Maps a QuickTime or ISO base media visual sample entry of uncompressed video.
     *
     * <p>A {@code raw } entry is RGB by its depth (16 bits: 5-5-5 big-endian, 24: red, green, blue, 32: alpha, red,
     * green, blue), palette indices at a depth of 8 with the color table the entry carries, or 8-bit gray at a depth
     * of 40 (a palette from white to black); its rows are padded to a multiple of 2 bytes. The other entries are
     * {@code 2vuy}, {@code yuvs}, {@code 24BG}, {@code BGRA}, {@code ABGR}, {@code RGBA}, {@code L565},
     * {@code B565}, {@code L555}, {@code b16g}, {@code b48r}, {@code b64a}, {@code v210} and {@code j420}, with rows
     * one after the other.
     *
     * @param type the sample entry type
     * @param depth the entry's depth
     * @param colorTable the entry's color table as {@code 0xAARRGGBB} entries, or {@code null} when it has none
     * @return the format, or {@code null} for another entry or an unsupported depth
     */
    public static RawVideoFormat sampleEntry(String type, int depth, int[] colorTable) {
        return switch (type) {
            case "raw " -> {
                var format = switch (depth) {
                    case 16 -> RawVideoFormat.of(RawPixelFormat.RGB555BE);
                    case 24 -> RawVideoFormat.of(RawPixelFormat.RGB24);
                    case 32 -> RawVideoFormat.of(RawPixelFormat.ARGB);
                    case 8 -> colorTable == null ? null
                            : RawVideoFormat.of(RawPixelFormat.PAL8).withPalette(colorTable);
                    case 40 -> RawVideoFormat.of(RawPixelFormat.PAL8).withPalette(invertedGray());
                    // TODO: QuickTime 1, 2 and 4-bit indices, and 8-bit indices into the default Macintosh palettes
                    default -> null;
                };
                yield format == null ? null : format.withRowAlignment(2);
            }
            case "2vuy" -> RawVideoFormat.of(RawPixelFormat.UYVY422);
            case "yuvs" -> RawVideoFormat.of(RawPixelFormat.YUYV422);
            case "24BG" -> RawVideoFormat.of(RawPixelFormat.BGR24);
            case "BGRA" -> RawVideoFormat.of(RawPixelFormat.BGRA);
            case "ABGR" -> RawVideoFormat.of(RawPixelFormat.ABGR);
            case "RGBA" -> RawVideoFormat.of(RawPixelFormat.RGBA);
            case "L565" -> RawVideoFormat.of(RawPixelFormat.RGB565LE);
            case "B565" -> RawVideoFormat.of(RawPixelFormat.RGB565BE);
            case "L555" -> RawVideoFormat.of(RawPixelFormat.RGB555LE);
            case "b16g" -> RawVideoFormat.of(RawPixelFormat.GRAY, 16).withBigEndian(true);
            case "b48r" -> RawVideoFormat.of(RawPixelFormat.RGB48BE);
            case "b64a" -> RawVideoFormat.of(RawPixelFormat.ARGB64BE);
            case "v210" -> RawVideoFormat.of(RawPixelFormat.V210);
            case "j420" -> RawVideoFormat.of(RawPixelFormat.YUV420P);
            default -> null;
        };
    }

    /**
     * Returns the QuickTime 8-bit gray palette: index 0 white, index 255 black.
     *
     * @return the palette
     */
    private static int[] invertedGray() {
        var palette = new int[256];
        for (var i = 0; i < 256; i++) {
            var level = 255 - i;
            palette[i] = 0xFF000000 | level << 16 | level << 8 | level;
        }
        return palette;
    }
}
