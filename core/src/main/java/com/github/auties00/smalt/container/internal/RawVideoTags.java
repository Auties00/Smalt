package com.github.auties00.smalt.container.internal;

import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.video.RawPixelFormat;
import com.github.auties00.smalt.video.RawVideoFormat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

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
     * Maps a {@code BITMAPINFOHEADER} of an uncompressed bitmap ({@code BI_RGB} at 1, 2, 4, 8, 16, 24 or 32 bits, or
     * {@code BI_BITFIELDS} at 16 bits with 5-5-5 or 5-6-5 masks) or of a FourCC layout.
     *
     * <p>Bitmaps store their rows bottom to top unless {@code biHeight} is negative, each row padded to a multiple of
     * 4 bytes; 32-bit pixels are blue, green, red, alpha; a bitmap of 8 bits or fewer takes its palette from the
     * {@code RGBQUAD} entries after the header ({@code biClrUsed} of them, or all its indices address when zero),
     * opaque.
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
                case 1 -> RawPixelFormat.PAL1;
                case 2 -> RawPixelFormat.PAL2;
                case 4 -> RawPixelFormat.PAL4;
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
        var indexBits = pixelFormat.indexBits();
        if (indexBits != 0) {
            var used = (int) Math.min(1 << indexBits, ByteArrays.u32le(header, 32));
            var entries = used == 0 ? 1 << indexBits : used;
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
     * green, blue), palette indices at a depth of 1, 2, 4 or 8 bits, or gray at a depth of 33, 34, 36 or 40 (indices
     * of 1, 2, 4 or 8 bits into a ramp from white to black); its rows are padded to a multiple of 2 bytes. Palette
     * indices take the color table the entry carries or, when it has none, the default Macintosh color table of
     * their depth: white and black at 1 bit, white, light gray, dark gray and black at 2 bits, the 16 colors of the
     * Macintosh 4-bit table and the 256 colors of the Macintosh 8-bit system table. The other entries are
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
                    case 1, 2, 4, 8 -> {
                        var pixelFormat = indexFormat(depth);
                        var entries = 1 << depth;
                        var palette = colorTable == null || colorTable.length == 0 ? macintoshPalette(depth)
                                : colorTable.length > entries ? Arrays.copyOf(colorTable, entries)
                                : colorTable;
                        yield RawVideoFormat.of(pixelFormat).withPalette(palette);
                    }
                    case 33, 34, 36, 40 -> RawVideoFormat.of(indexFormat(depth - 32))
                            .withPalette(invertedGray(1 << (depth - 32)));
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
     * Returns the palette format of an index size.
     *
     * @param bits 1, 2, 4 or 8
     * @return the format
     */
    private static RawPixelFormat indexFormat(int bits) {
        return switch (bits) {
            case 1 -> RawPixelFormat.PAL1;
            case 2 -> RawPixelFormat.PAL2;
            case 4 -> RawPixelFormat.PAL4;
            default -> RawPixelFormat.PAL8;
        };
    }

    /**
     * Returns a QuickTime gray palette: index 0 white, the last index black, the levels evenly spaced.
     *
     * @param entries the number of levels, 2 to 256
     * @return the palette
     */
    private static int[] invertedGray(int entries) {
        var palette = new int[entries];
        for (var i = 0; i < entries; i++) {
            var level = 255 - 255 * i / (entries - 1);
            palette[i] = 0xFF000000 | level << 16 | level << 8 | level;
        }
        return palette;
    }

    /**
     * Returns the default Macintosh color table of a depth.
     *
     * <p>The 8-bit system table is the 6x6x6 cube of the levels {@code FF, CC, 99, 66, 33, 00} without black, red
     * varying slowest, then ramps of red, green, blue and gray through the ten levels {@code EE, DD, BB, AA, 88, 77,
     * 55, 44, 22, 11} that are not cube levels, then black.
     *
     * @param depth 1, 2, 4 or 8
     * @return the opaque palette of {@code 2^depth} entries
     */
    private static int[] macintoshPalette(int depth) {
        return switch (depth) {
            case 1 -> new int[]{0xFFFFFFFF, 0xFF000000};
            case 2 -> new int[]{0xFFFFFFFF, 0xFFACACAC, 0xFF555555, 0xFF000000};
            case 4 -> new int[]{
                    0xFFFFFFFF, 0xFFFCF305, 0xFFFF6402, 0xFFDD0806, 0xFFF20884, 0xFF4600A5, 0xFF0000D4, 0xFF02ABEA,
                    0xFF1FB714, 0xFF006411, 0xFF562C05, 0xFF90713A, 0xFFC0C0C0, 0xFF808080, 0xFF404040, 0xFF000000};
            default -> {
                var palette = new int[256];
                for (var i = 0; i < 215; i++) {
                    var red = 0xFF - 0x33 * (i / 36);
                    var green = 0xFF - 0x33 * (i / 6 % 6);
                    var blue = 0xFF - 0x33 * (i % 6);
                    palette[i] = 0xFF000000 | red << 16 | green << 8 | blue;
                }
                var levels = new int[]{0xEE, 0xDD, 0xBB, 0xAA, 0x88, 0x77, 0x55, 0x44, 0x22, 0x11};
                for (var i = 0; i < 10; i++) {
                    var level = levels[i];
                    palette[215 + i] = 0xFF000000 | level << 16;
                    palette[225 + i] = 0xFF000000 | level << 8;
                    palette[235 + i] = 0xFF000000 | level;
                    palette[245 + i] = 0xFF000000 | level << 16 | level << 8 | level;
                }
                palette[255] = 0xFF000000;
                yield palette;
            }
        };
    }
}
