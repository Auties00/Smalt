package com.github.auties00.smalt.probe;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.io.SourceReader;
import com.github.auties00.smalt.io.MediaSource;

import java.nio.charset.StandardCharsets;

/**
 * Describes the images of the simple raster formats from their headers: Netpbm and the float maps, TGA, QOI,
 * Radiance RGBE, ICNS, Photoshop, OpenEXR, JPEG XR and DDS.
 *
 * <p>Each reader gives the stored size, the bits per sample (32 for floating-point samples, 16 for half floats), the
 * declared transparency and, for the formats that hold several images, their number, as the format's decoder
 * reports them.
 */
final class RasterHeaders {
    /**
     * The leading bytes read for a text header.
     */
    private static final int TEXT_HEADER = 8192;

    /**
     * The most images or entries a reader walks.
     */
    private static final int MAX_ENTRIES = 1 << 16;

    private RasterHeaders() {
        throw new AssertionError();
    }

    /**
     * Reads the first header of a Netpbm or float map file and counts the raw images that follow it; a plain image
     * followed by more data leaves the count unknown.
     */
    static ImageHeaders.Header pnm(MediaSource source) {
        var result = new ImageHeaders.Header();
        var size = source.size();
        var offset = 0L;
        var count = 0;
        for (var step = 0; step < MAX_ENTRIES; step++) {
            var head = new byte[TEXT_HEADER];
            var length = SourceBytes.readAvailable(source, offset, head, 0, head.length);
            var text = new PnmText(head, length);
            text.skipSpace();
            if (!text.magic()) {
                if (count == 0) {
                    throw new MediaException.Malformed("the file does not start with a Netpbm magic number");
                }
                break;
            }
            var kind = head[text.position - 1];
            var width = kind == '7' ? 0 : text.number();
            var height = kind == '7' ? 0 : text.number();
            var depth = 1;
            var maxval = 1;
            var alpha = false;
            var bits = 0;
            var plain = kind == '1' || kind == '2' || kind == '3';
            switch (kind) {
                case '1', '4' -> bits = 1;
                case '2', '5', '3', '6' -> {
                    maxval = text.number();
                    depth = kind == '3' || kind == '6' ? 3 : 1;
                }
                case 'F', 'f', 'H', 'h' -> {
                    text.token();
                    depth = kind == 'F' || kind == 'H' ? 3 : 1;
                    bits = kind == 'F' || kind == 'f' ? 32 : 16;
                }
                default -> {
                    var pam = text.pam();
                    width = pam[0];
                    height = pam[1];
                    depth = pam[2];
                    maxval = pam[3];
                    alpha = pam[4] != 0;
                }
            }
            if (width <= 0 || height <= 0) {
                throw new MediaException.Malformed("the Netpbm header declares no picture size");
            }
            if (bits == 0) {
                bits = 32 - Integer.numberOfLeadingZeros(Math.max(1, maxval));
            }
            if (count == 0) {
                result.width = width;
                result.height = height;
                result.bitDepth = bits;
                result.alpha = alpha;
            }
            count++;
            if (plain) {
                result.frames = size >= 0 && offset + text.position + 1 >= size ? count : -1;
                return result;
            }
            var sampleBytes = kind == '4' ? 0 : kind == 'F' || kind == 'f' ? 4 : kind == 'H' || kind == 'h' ? 2
                    : maxval > 255 ? 2 : 1;
            var rowBytes = sampleBytes == 0 ? (width + 7L) / 8 : (long) width * depth * sampleBytes;
            offset += text.position + 1 + rowBytes * height;
            if (size >= 0 && offset >= size) {
                break;
            }
        }
        result.frames = count;
        return result;
    }

    /**
     * Reads a TGA header: alpha is declared by a positive attribute bit count in the image descriptor.
     */
    static ImageHeaders.Header tga(MediaSource source) {
        var result = new ImageHeaders.Header();
        var reader = new SourceReader(source, 64);
        reader.seek(12);
        result.width = reader.u16le();
        result.height = reader.u16le();
        var depth = reader.u8();
        var descriptor = reader.u8();
        reader.seek(2);
        var type = reader.u8();
        var gray = type == 3 || type == 11;
        result.bitDepth = !gray && (depth == 15 || depth == 16) ? 5 : 8;
        result.alpha = (descriptor & 0x0F) > 0;
        return result;
    }

    /**
     * Reads a QOI header.
     */
    static ImageHeaders.Header qoi(MediaSource source) {
        var result = new ImageHeaders.Header();
        var reader = new SourceReader(source, 64);
        reader.seek(4);
        result.width = (int) Math.min(Integer.MAX_VALUE, reader.u32be());
        result.height = (int) Math.min(Integer.MAX_VALUE, reader.u32be());
        result.alpha = reader.u8() == 4;
        result.bitDepth = 8;
        return result;
    }

    /**
     * Reads a Radiance RGBE header up to its resolution line.
     */
    static ImageHeaders.Header hdr(MediaSource source) {
        var result = new ImageHeaders.Header();
        var head = new byte[TEXT_HEADER];
        var length = SourceBytes.readAvailable(source, 0, head, 0, head.length);
        var text = new String(head, 0, length, StandardCharsets.ISO_8859_1);
        var blank = text.indexOf("\n\n");
        if (blank < 0) {
            throw new MediaException.Malformed("the Radiance header has no end");
        }
        var end = text.indexOf('\n', blank + 2);
        var line = (end < 0 ? text.substring(blank + 2) : text.substring(blank + 2, end)).strip().split("\\s+");
        if (line.length != 4) {
            throw new MediaException.Malformed("invalid Radiance resolution line");
        }
        var first = parse(line[1]);
        var second = parse(line[3]);
        var rowsFirst = line[0].endsWith("Y");
        result.width = rowsFirst ? second : first;
        result.height = rowsFirst ? first : second;
        result.bitDepth = 32;
        return result;
    }

    /**
     * Reads the entries of an ICNS file: the size is that of its largest image, alpha is declared by an image type
     * that carries it or by a mask entry, and the frames are its images and those of the dark appearance it nests.
     */
    static ImageHeaders.Header icns(MediaSource source) {
        var result = new ImageHeaders.Header();
        var reader = new SourceReader(source, 4096);
        var count = icnsFamily(reader, 0, source.size() >= 0 ? source.size() : Long.MAX_VALUE, result, false);
        if (count == 0) {
            throw new MediaException.Malformed("the ICNS file holds no image");
        }
        result.bitDepth = 8;
        result.frames = count;
        return result;
    }

    /**
     * Reads the elements of one ICNS icon family into a header.
     *
     * @param reader the input
     * @param start the offset of the family's {@code icns} header
     * @param end the offset past the bytes available to the family
     * @param result the header the size and alpha are recorded in
     * @param nested whether the family is the nested dark appearance, which nests no other
     * @return the number of images
     */
    private static int icnsFamily(SourceReader reader, long start, long end, ImageHeaders.Header result,
                                  boolean nested) {
        reader.seek(start + 4);
        var total = Math.min(start + reader.u32be(), end);
        var position = start + 8;
        var count = 0;
        for (var step = 0; step < MAX_ENTRIES && position + 8 <= total; step++) {
            reader.seek(position);
            var bytes = new byte[]{(byte) reader.u8(), (byte) reader.u8(), (byte) reader.u8(), (byte) reader.u8()};
            var type = new String(bytes, StandardCharsets.ISO_8859_1);
            var length = reader.u32be();
            if (length < 8) {
                throw new MediaException.Malformed("ICNS entry " + type + " is shorter than its header");
            }
            if (position + length > total) {
                break;
            }
            var side = icnsSide(type);
            if (side > 0) {
                count++;
                if (side > result.width) {
                    result.width = side;
                    result.height = side;
                }
                result.alpha |= icnsAlpha(type);
            }
            result.alpha |= type.equals("s8mk") || type.equals("l8mk") || type.equals("h8mk") || type.equals("t8mk");
            if (!nested && length >= 16 && (bytes[0] & 0xFF) == 0xFD && (bytes[1] & 0xFF) == 0xD9 && bytes[2] == 0x2F
                    && (bytes[3] & 0xFF) == 0xA8) {
                reader.seek(position + 8);
                if (reader.u8() == 'i' && reader.u8() == 'c' && reader.u8() == 'n' && reader.u8() == 's') {
                    count += icnsFamily(reader, position + 8, position + length, result, true);
                }
            }
            position += length;
        }
        return count;
    }

    /**
     * {@return the pixel side of an ICNS image entry type, or 0 for an entry that is not an image}
     *
     * @param type the four-character type
     */
    static int icnsSide(String type) {
        return switch (type) {
            case "icm#", "icm4", "icm8" -> 16;
            case "ics#", "ics4", "ics8", "is32", "icp4", "ic04" -> 16;
            case "icsb" -> 18;
            case "sb24" -> 24;
            case "ICON", "ICN#", "icl4", "icl8", "il32", "icp5", "ic11", "ic05" -> 32;
            case "icsB" -> 36;
            case "ich#", "ich4", "ich8", "ih32", "icp6", "SB24" -> 48;
            case "ic12" -> 64;
            case "it32", "ic07" -> 128;
            case "ic08", "ic13" -> 256;
            case "ic09", "ic14" -> 512;
            case "ic10" -> 1024;
            default -> 0;
        };
    }

    /**
     * {@return whether an ICNS image entry type carries transparency itself}
     */
    private static boolean icnsAlpha(String type) {
        return switch (type) {
            case "ICN#", "icm#", "ics#", "ich#", "icp4", "icp5", "icp6", "ic04", "ic05", "ic07", "ic08", "ic09",
                 "ic10", "ic11", "ic12", "ic13", "ic14", "icsb", "icsB", "sb24", "SB24" -> true;
            default -> false;
        };
    }

    /**
     * Reads a Photoshop header and the layer count of its layer section (of its {@code Lr16} or {@code Lr32} block
     * for a 16 or 32-bit document): the composite has alpha when it has more channels than its color mode and the
     * layer count is negative, or when it is indexed and its transparency index resource names an entry.
     */
    static ImageHeaders.Header psd(MediaSource source) {
        var result = new ImageHeaders.Header();
        var reader = new SourceReader(source, 4096);
        reader.seek(4);
        var version = reader.u16be();
        reader.skip(6);
        var channels = reader.u16be();
        result.height = (int) Math.min(Integer.MAX_VALUE, reader.u32be());
        result.width = (int) Math.min(Integer.MAX_VALUE, reader.u32be());
        var depth = reader.u16be();
        var mode = reader.u16be();
        result.bitDepth = depth;
        var colors = switch (mode) {
            case 3, 4 -> mode == 3 ? 3 : 4;
            case 9 -> 3;
            case 2, 0, 1, 8 -> 1;
            default -> channels;
        };
        reader.skip(reader.u32be());
        var resourcesLength = reader.u32be();
        var resourcesEnd = reader.position() + resourcesLength;
        if (mode == 2) {
            result.alpha = psdTransparentIndex(reader, resourcesEnd);
        }
        if (channels > colors) {
            reader.seek(resourcesEnd);
            var large = version == 2;
            var sectionLength = large ? reader.u64be() : reader.u32be();
            var sectionEnd = reader.position() + sectionLength;
            if (sectionLength >= (large ? 10 : 6)) {
                var infoLength = large ? reader.u64be() : reader.u32be();
                if (infoLength >= 2) {
                    result.alpha |= (short) reader.u16be() < 0;
                } else if (infoLength >= 0) {
                    reader.skip(infoLength);
                    result.alpha |= psdTaggedLayerCount(reader, sectionEnd, large) < 0;
                }
            }
        }
        return result;
    }

    /**
     * Returns whether the image resources of an indexed Photoshop document hold a transparency index (resource
     * 1047) naming one of the 256 entries of its color table.
     */
    private static boolean psdTransparentIndex(SourceReader reader, long end) {
        for (var step = 0; step < MAX_ENTRIES && reader.position() + 12 <= end; step++) {
            var signature = reader.fourCc();
            if (signature != 0x3842494D) {
                return false;
            }
            var id = reader.u16be();
            var nameLength = reader.u8();
            reader.skip(((1 + nameLength + 1) & ~1) - 1);
            var size = reader.u32be();
            if (id == 1047) {
                return size >= 2 && reader.u16be() < 256;
            }
            reader.skip((size + 1) & ~1L);
        }
        return false;
    }

    /**
     * Returns the layer count of the {@code Lr16} or {@code Lr32} additional layer information that follows the
     * global layer mask, or 0 when there is none.
     */
    private static int psdTaggedLayerCount(SourceReader reader, long end, boolean large) {
        if (reader.position() + 4 > end) {
            return 0;
        }
        reader.skip(reader.u32be());
        for (var step = 0; step < MAX_ENTRIES && reader.position() + 12 <= end; step++) {
            var signature = reader.fourCc();
            if (signature != 0x3842494D && signature != 0x38423634) {
                return 0;
            }
            var key = new String(reader.bytes(4, 4), StandardCharsets.ISO_8859_1);
            var wide = large && switch (key) {
                case "LMsk", "Lr16", "Lr32", "Layr", "Mt16", "Mt32", "Mtrn", "Alph", "FMsk", "lnk2", "FEid", "FXid",
                     "PxSD" -> true;
                default -> false;
            };
            var length = wide ? reader.u64be() : reader.u32be();
            if (length < 0) {
                return 0;
            }
            if (key.equals("Lr16") || key.equals("Lr32")) {
                return length >= 2 ? (short) reader.u16be() : 0;
            }
            reader.skip((length + 1) & ~1L);
        }
        return 0;
    }

    /**
     * Reads the first header of an OpenEXR file: the size of its data window, the widest channel and whether a
     * channel is named {@code A}; the frames are its parts.
     */
    static ImageHeaders.Header exr(MediaSource source) {
        var result = new ImageHeaders.Header();
        var reader = new SourceReader(source, 8192);
        reader.seek(4);
        var flags = reader.u32le();
        var multipart = (flags & 0x1000) != 0;
        var found = false;
        for (var step = 0; step < MAX_ENTRIES; step++) {
            var name = cString(reader);
            if (name.isEmpty()) {
                break;
            }
            var type = cString(reader);
            var size = reader.u32le();
            var next = reader.position() + size;
            if (name.equals("dataWindow") && type.equals("box2i") && size == 16) {
                var xMin = reader.s32le();
                var yMin = reader.s32le();
                var xMax = reader.s32le();
                var yMax = reader.s32le();
                result.width = (int) Math.min(Integer.MAX_VALUE, Math.max(0, (long) xMax - xMin + 1));
                result.height = (int) Math.min(Integer.MAX_VALUE, Math.max(0, (long) yMax - yMin + 1));
                found = true;
            } else if (name.equals("channels") && type.equals("chlist")) {
                var end = next - 1;
                while (reader.position() < end) {
                    var channel = cString(reader);
                    var pixelType = reader.u32le();
                    reader.skip(12);
                    result.bitDepth = Math.max(result.bitDepth, pixelType == 1 ? 16 : 32);
                    result.alpha |= channel.equals("A") || channel.endsWith(".A");
                }
            }
            reader.seek(next);
        }
        if (!found) {
            throw new MediaException.Malformed("the OpenEXR header has no data window");
        }
        if (multipart) {
            var parts = 1;
            for (var step = 0; step < MAX_ENTRIES; step++) {
                var name = cString(reader);
                if (name.isEmpty()) {
                    break;
                }
                parts++;
                for (var attribute = 0; attribute < MAX_ENTRIES && !name.isEmpty(); attribute++) {
                    cString(reader);
                    reader.skip(reader.u32le());
                    name = cString(reader);
                }
            }
            result.frames = parts;
        }
        return result;
    }

    /**
     * The first 15 bytes of the pixel format GUIDs of the common JPEG XR family, whose last byte names the format.
     */
    private static final byte[] JXR_COMMON_GUID = {0x24, (byte) 0xC3, (byte) 0xDD, 0x6F, 0x03, 0x4E, (byte) 0xFE,
            0x4B, (byte) 0xB1, (byte) 0x85, 0x3D, 0x77, 0x76, (byte) 0x8D, (byte) 0xC9};

    /**
     * Reads the first directory of a JPEG XR file: its size, the bits per sample of its pixel format, and alpha from
     * its pixel format or a planar alpha image.
     */
    static ImageHeaders.Header jxr(MediaSource source) {
        var result = new ImageHeaders.Header();
        var reader = new SourceReader(source, 4096);
        reader.seek(4);
        reader.seek(reader.u32le());
        var count = reader.u16le();
        var format = -1;
        for (var index = 0; index < count && index < MAX_ENTRIES; index++) {
            var tag = reader.u16le();
            var type = reader.u16le();
            var values = reader.u32le();
            var value = reader.u32le();
            var number = type == 3 && values == 1 ? value & 0xFFFF : value;
            switch (tag) {
                case 0xBC80 -> result.width = (int) Math.min(Integer.MAX_VALUE, number);
                case 0xBC81 -> result.height = (int) Math.min(Integer.MAX_VALUE, number);
                case 0xBCC2 -> result.alpha = true;
                case 0xBC01 -> {
                    var back = reader.position();
                    reader.seek(value);
                    var guid = reader.bytes(16, 16);
                    var common = java.util.Arrays.equals(guid, 0, 15, JXR_COMMON_GUID, 0, 15);
                    // the four formats outside the common family: 32bppRGBA, 32bppPRGBA, 32bppRGB and 96bppRGBFloat,
                    // mapped to the common formats of their depth and alpha
                    format = common ? guid[15] & 0xFF : switch (guid[15] & 0xFF) {
                        case 0xE9, 0xBA -> 0x0F;
                        case 0x27 -> 0x1B;
                        default -> 0x0E;
                    };
                    reader.seek(back);
                }
                default -> {
                }
            }
        }
        result.bitDepth = jxrBits(format);
        result.alpha |= jxrAlpha(format);
        return result;
    }

    /**
     * {@return the bits per sample of a JPEG XR pixel format of the common family, named by the last byte of its
     * GUID: 1 for black and white; 32 for 32-bit floating-point and fixed-point samples and RGBE; 16 for 16-bit
     * integer, fixed-point and half samples and 10-bit samples; 8 otherwise}
     */
    private static int jxrBits(int format) {
        return switch (format) {
            case 0x05 -> 1;
            case 0x11, 0x18, 0x19, 0x1A, 0x1B, 0x1E, 0x3D, 0x3F, 0x41 -> 32;
            case 0x0B, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x1D, 0x1F, 0x26, 0x27, 0x28, 0x29, 0x2A, 0x2B, 0x2D,
                 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x3B, 0x3E, 0x40, 0x42, 0x46, 0x47, 0x49, 0x4A, 0x4B -> 16;
            default -> 8;
        };
    }

    /**
     * {@return whether a JPEG XR pixel format, named by the last byte of its GUID, carries alpha}
     */
    private static boolean jxrAlpha(int format) {
        return switch (format) {
            case 0x0F, 0x10, 0x16, 0x17, 0x19, 0x1A, 0x1D, 0x1E, 0x2C, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x32, 0x33,
                 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x4C, 0x4D -> true;
            default -> false;
        };
    }

    /**
     * Reads the picture header of a JPEG XS codestream, bare or in the {@code jp2c} box of a JXS file: the size of
     * the picture (twice the sampling grid for a Bayer codestream), the largest component precision and alpha for four
     * components other than Bayer data.
     */
    static ImageHeaders.Header jpegXs(MediaSource source) {
        var result = new ImageHeaders.Header();
        var head = new byte[1 << 16];
        var length = SourceBytes.readAvailable(source, 0, head, 0, head.length);
        var start = 0;
        if (length >= 12 && head[4] == 'J' && head[5] == 'X' && head[6] == 'S' && head[7] == ' ') {
            start = -1;
            var position = 12;
            while (position + 8 <= length) {
                var size = (head[position] & 0xFFL) << 24 | (head[position + 1] & 0xFF) << 16
                        | (head[position + 2] & 0xFF) << 8 | head[position + 3] & 0xFF;
                if (head[position + 4] == 'j' && head[position + 5] == 'p' && head[position + 6] == '2'
                        && head[position + 7] == 'c') {
                    start = position + 8;
                    break;
                }
                if (size < 8) {
                    break;
                }
                position += (int) Math.min(size, Integer.MAX_VALUE - position);
            }
            if (start < 0) {
                throw new MediaException.Malformed("the JXS file has no codestream box in its first "
                        + head.length + " bytes");
            }
        }
        // SOC, CAP (marker, length, bits), then PIH (marker, length) and its fields
        if (start + 6 > length) {
            throw new MediaException.Malformed("the JPEG XS codestream is truncated");
        }
        var cap = (head[start + 4] & 0xFF) << 8 | head[start + 5] & 0xFF;
        var pih = start + 4 + cap;
        if (pih + 28 > length || (head[pih] & 0xFF) != 0xFF || (head[pih + 1] & 0xFF) != 0x12) {
            throw new MediaException.Malformed("the JPEG XS codestream has no picture header after CAP");
        }
        var width = (head[pih + 12] & 0xFF) << 8 | head[pih + 13] & 0xFF;
        var height = (head[pih + 14] & 0xFF) << 8 | head[pih + 15] & 0xFF;
        var components = head[pih + 20] & 0xFF;
        var bayer = (head[pih + 25] & 0x0F) == 3;
        result.width = bayer ? width * 2 : width;
        result.height = bayer ? height * 2 : height;
        result.alpha = !bayer && components == 4;
        var cdt = pih + 28;
        while (cdt + 4 <= length && (head[cdt] & 0xFF) == 0xFF && (head[cdt + 1] & 0xFF) != 0x13
                && (head[cdt + 1] & 0xFF) != 0x20) {
            cdt += 2 + ((head[cdt + 2] & 0xFF) << 8 | head[cdt + 3] & 0xFF);
        }
        if (cdt + 4 + 2 * components <= length && (head[cdt] & 0xFF) == 0xFF && (head[cdt + 1] & 0xFF) == 0x13) {
            for (var c = 0; c < components; c++) {
                result.bitDepth = Math.max(result.bitDepth, head[cdt + 4 + 2 * c] & 0xFF);
            }
        }
        return result;
    }

    /**
     * Reads a DDS header: the size of the top surface, the depth of its pixel format, alpha from the format, and as
     * frames the surfaces of every array element, face, mipmap level and depth slice.
     */
    static ImageHeaders.Header dds(MediaSource source) {
        var result = new ImageHeaders.Header();
        var reader = new SourceReader(source, 256);
        reader.seek(12);
        result.height = (int) Math.min(Integer.MAX_VALUE, reader.u32le());
        result.width = (int) Math.min(Integer.MAX_VALUE, reader.u32le());
        reader.u32le();
        var depth = Math.max(1, reader.u32le());
        var mips = Math.max(1, reader.u32le());
        reader.seek(80);
        var pixelFlags = reader.u32le();
        var fourCc = reader.u32le();
        var bits = reader.u32le();
        reader.seek(104);
        var alphaMask = reader.u32le();
        reader.seek(112);
        var caps2 = reader.u32le();
        var faces = (caps2 & 0x200) != 0 ? Math.max(1, Long.bitCount(caps2 & 0xFC00)) : 1;
        var volume = (caps2 & 0x200000) != 0;
        var elements = 1L;
        result.bitDepth = 8;
        if ((pixelFlags & 0x4) != 0) {
            var code = new String(new byte[]{(byte) fourCc, (byte) (fourCc >>> 8), (byte) (fourCc >>> 16),
                    (byte) (fourCc >>> 24)}, StandardCharsets.ISO_8859_1);
            switch (code) {
                case "DXT1", "DXT2", "DXT3", "DXT4", "DXT5" -> result.alpha = true;
                case "DX10" -> {
                    reader.seek(128);
                    var dxgi = (int) reader.u32le();
                    reader.u32le();
                    var misc = reader.u32le();
                    elements = Math.max(1, reader.u32le());
                    faces = (misc & 0x4) != 0 ? 6 : 1;
                    result.alpha = DdsFormats.alpha(dxgi);
                    result.bitDepth = DdsFormats.bits(dxgi);
                }
                default -> {
                    // the Direct3D 9 format codes of 16-bit and floating-point surfaces
                    switch ((int) fourCc) {
                        case 36, 113 -> {
                            result.bitDepth = 16;
                            result.alpha = true;
                        }
                        case 110, 111, 112 -> result.bitDepth = 16;
                        case 114, 115 -> result.bitDepth = 32;
                        case 116 -> {
                            result.bitDepth = 32;
                            result.alpha = true;
                        }
                        default -> {
                        }
                    }
                }
            }
        } else {
            result.alpha = (pixelFlags & 0x3) != 0 && alphaMask != 0;
            result.bitDepth = bits > 32 ? 16 : 8;
        }
        var surfaces = 0L;
        for (var level = 0L; level < mips && level < 32; level++) {
            surfaces += volume ? Math.max(1, depth >> level) : 1;
        }
        result.frames = Math.min(Integer.MAX_VALUE, surfaces * faces * elements);
        return result;
    }

    /**
     * Reads a NUL-terminated name of at most 255 characters.
     */
    private static String cString(SourceReader reader) {
        var builder = new StringBuilder();
        for (var count = 0; count < 256; count++) {
            var value = reader.u8();
            if (value == 0) {
                return builder.toString();
            }
            builder.append((char) value);
        }
        throw new MediaException.Malformed("an OpenEXR name is longer than 255 characters");
    }

    /**
     * Parses a positive decimal dimension.
     */
    private static int parse(String text) {
        try {
            var value = Integer.parseInt(text);
            if (value <= 0) {
                throw new MediaException.Malformed("invalid dimension " + text);
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new MediaException.Malformed("invalid dimension " + text, exception);
        }
    }

    /**
     * Reads the white-space separated fields of a Netpbm header.
     */
    private static final class PnmText {
        final byte[] data;
        final int length;
        int position;

        PnmText(byte[] data, int length) {
            this.data = data;
            this.length = length;
        }

        void skipSpace() {
            while (position < length) {
                var c = data[position];
                if (c == ' ' || (c >= 9 && c <= 13)) {
                    position++;
                } else if (c == '#') {
                    while (position < length && data[position] != '\n' && data[position] != '\r') {
                        position++;
                    }
                } else {
                    return;
                }
            }
        }

        boolean magic() {
            if (position + 2 >= length || data[position] != 'P') {
                return false;
            }
            var kind = data[position + 1];
            var space = data[position + 2];
            if (!(space == ' ' || (space >= 9 && space <= 13))) {
                return false;
            }
            switch (kind) {
                case '1', '2', '3', '4', '5', '6', '7', 'F', 'f', 'H', 'h' -> {
                    position += 2;
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }

        String token() {
            skipSpace();
            var begin = position;
            while (position < length && !(data[position] == ' ' || (data[position] >= 9 && data[position] <= 13))
                    && data[position] != '#' && position - begin < 64) {
                position++;
            }
            if (begin == position) {
                throw new MediaException.Malformed("the Netpbm header is truncated");
            }
            return new String(data, begin, position - begin, StandardCharsets.ISO_8859_1);
        }

        int number() {
            var token = token();
            try {
                return Integer.parseInt(token);
            } catch (NumberFormatException exception) {
                throw new MediaException.Malformed("invalid Netpbm header field " + token, exception);
            }
        }

        /**
         * Reads a PAM header up to its {@code ENDHDR} line: width, height, depth, maxval and 1 when the tuple type
         * has alpha.
         */
        int[] pam() {
            var result = new int[5];
            for (var lines = 0; lines < 4096; lines++) {
                skipSpace();
                if (position >= length) {
                    throw new MediaException.Malformed("the PAM header has no ENDHDR line");
                }
                var lineEnd = position;
                while (lineEnd < length && data[lineEnd] != '\n') {
                    lineEnd++;
                }
                var line = new String(data, position, lineEnd - position, StandardCharsets.ISO_8859_1).strip();
                var space = line.indexOf(' ');
                var key = space < 0 ? line : line.substring(0, space);
                var value = space < 0 ? "" : line.substring(space + 1).strip();
                if (key.equals("ENDHDR")) {
                    position = lineEnd;
                    return result;
                }
                position = lineEnd;
                try {
                    switch (key) {
                        case "WIDTH" -> result[0] = Integer.parseInt(value);
                        case "HEIGHT" -> result[1] = Integer.parseInt(value);
                        case "DEPTH" -> result[2] = Integer.parseInt(value);
                        case "MAXVAL" -> result[3] = Integer.parseInt(value);
                        case "TUPLTYPE" -> result[4] |= value.toUpperCase(java.util.Locale.ROOT).endsWith("_ALPHA")
                                ? 1 : 0;
                        default -> {
                        }
                    }
                } catch (NumberFormatException exception) {
                    throw new MediaException.Malformed("invalid PAM header line " + line, exception);
                }
            }
            throw new MediaException.Malformed("the PAM header has no ENDHDR line");
        }
    }
}
