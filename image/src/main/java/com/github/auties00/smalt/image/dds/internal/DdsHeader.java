package com.github.auties00.smalt.image.dds.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.dds.internal.DdsFormat.Field;
import com.github.auties00.smalt.image.dds.internal.DdsFormat.Kind;
import com.github.auties00.smalt.image.dds.internal.DdsFormat.Type;
import com.github.auties00.smalt.internal.io.ByteArrays;

import java.util.ArrayList;
import java.util.List;

/**
 * The header of a DDS file, with its optional DX10 extension, and the surfaces it lays out.
 *
 * @param width the width of the top mipmap level, positive
 * @param height the height of the top mipmap level, positive
 * @param depth the depth of the top level of a volume texture, 1 otherwise
 * @param mipmaps the number of mipmap levels, at least 1
 * @param faces the cube faces present, in file order, as indices 0 (+X) to 5 (-Z); one entry 0 for a texture that is
 * not a cube map
 * @param elements the number of array elements, at least 1
 * @param format the pixel format
 * @param dataOffset the offset of the first surface
 */
public record DdsHeader(int width, int height, int depth, int mipmaps, int[] faces, int elements, DdsFormat format,
                        int dataOffset) {
    private static final int DDPF_ALPHAPIXELS = 0x1;
    private static final int DDPF_ALPHA = 0x2;
    private static final int DDPF_FOURCC = 0x4;
    private static final int DDPF_YUV = 0x200;
    private static final int DDPF_RGB = 0x40;
    private static final int DDPF_LUMINANCE = 0x20000;
    private static final int DDPF_BUMPDUDV = 0x80000;
    private static final int DDSCAPS2_CUBEMAP = 0x200;
    private static final int DDSCAPS2_VOLUME = 0x200000;

    /**
     * The most mipmap levels a texture can have.
     */
    private static final int MAX_LEVELS = 32;

    /**
     * One surface of the file.
     *
     * @param element the array element
     * @param face the cube face, 0 (+X) to 5 (-Z), or 0 for a texture that is not a cube map
     * @param level the mipmap level
     * @param slice the depth slice of a volume texture, 0 otherwise
     * @param width the width, positive
     * @param height the height, positive
     * @param offset the offset of its texels
     */
    public record Surface(int element, int face, int level, int slice, int width, int height, long offset) {
    }

    /**
     * Parses a header.
     *
     * @param data the file, at least its headers
     * @return the header
     * @throws MediaException.Malformed if the header is invalid
     * @throws MediaException.Unsupported if the pixel format is not supported
     */
    public static DdsHeader parse(byte[] data) {
        if (data.length < 128 || data[0] != 'D' || data[1] != 'D' || data[2] != 'S' || data[3] != ' '
                || ByteArrays.u32le(data, 4) != 124) {
            throw new MediaException.Malformed("the file does not start with a DDS header");
        }
        var height = ByteArrays.u32le(data, 12);
        var width = ByteArrays.u32le(data, 16);
        var depth = Math.max(1, ByteArrays.u32le(data, 24));
        var mipmaps = Math.max(1, ByteArrays.u32le(data, 28));
        var pixelFlags = (int) ByteArrays.u32le(data, 80);
        var fourCc = (int) ByteArrays.u32le(data, 84);
        var bitCount = (int) ByteArrays.u32le(data, 88);
        var masks = new long[]{ByteArrays.u32le(data, 92), ByteArrays.u32le(data, 96), ByteArrays.u32le(data, 100),
                ByteArrays.u32le(data, 104)};
        var caps2 = (int) ByteArrays.u32le(data, 112);
        if (width == 0 || height == 0 || width > Integer.MAX_VALUE || height > Integer.MAX_VALUE) {
            throw new MediaException.Malformed("invalid DDS size " + width + "x" + height);
        }
        if (mipmaps > MAX_LEVELS || depth > Integer.MAX_VALUE) {
            throw new MediaException.Malformed("invalid DDS mipmap count " + mipmaps + " or depth " + depth);
        }
        var volume = (caps2 & DDSCAPS2_VOLUME) != 0;
        int[] faces = {0};
        if ((caps2 & DDSCAPS2_CUBEMAP) != 0) {
            var present = new ArrayList<Integer>();
            for (var face = 0; face < 6; face++) {
                if ((caps2 & (0x400 << face)) != 0) {
                    present.add(face);
                }
            }
            if (!present.isEmpty()) {
                faces = present.stream().mapToInt(Integer::intValue).toArray();
            }
        }
        var elements = 1;
        var offset = 128;
        DdsFormat format;
        if ((pixelFlags & DDPF_FOURCC) != 0 && fourCc == fourCc("DX10")) {
            if (data.length < 148) {
                throw new MediaException.Malformed("the DDS DX10 header is truncated");
            }
            var dxgi = (int) ByteArrays.u32le(data, 128);
            var dimension = (int) ByteArrays.u32le(data, 132);
            var misc = (int) ByteArrays.u32le(data, 136);
            var arraySize = ByteArrays.u32le(data, 140);
            var misc2 = (int) ByteArrays.u32le(data, 144);
            if (arraySize == 0 || arraySize > 1 << 16) {
                throw new MediaException.Malformed("invalid DDS array size " + arraySize);
            }
            elements = (int) arraySize;
            faces = (misc & 0x4) != 0 ? new int[]{0, 1, 2, 3, 4, 5} : new int[]{0};
            volume = dimension == 4;
            if (!volume) {
                depth = 1;
            }
            format = dxgi(dxgi);
            if ((misc2 & 0x7) == 2) {
                format = format.premultiplied();
            }
            offset = 148;
        } else if ((pixelFlags & DDPF_FOURCC) != 0) {
            format = fourCcFormat(fourCc);
        } else if ((pixelFlags & DDPF_YUV) != 0) {
            // TODO: decode the legacy YUV pixel formats (DDPF_YUV with channel masks)
            throw new MediaException.Unsupported("dds", "YUV pixel formats");
        } else {
            format = masked(pixelFlags, bitCount, masks);
        }
        if (!volume) {
            depth = 1;
        }
        return new DdsHeader((int) width, (int) height, (int) depth, (int) mipmaps, faces, elements, format, offset);
    }

    /**
     * Lists the surfaces in file order: every array element, then every face, then every mipmap level, then every
     * depth slice of it.
     *
     * @param fileSize the size of the file, to stop at the first surface past its end
     * @param maxSurfaces the most surfaces to list
     * @return the surfaces that lie inside the file, at least the first
     * @throws MediaException.Malformed if the first surface does not fit the file
     */
    public List<Surface> surfaces(long fileSize, int maxSurfaces) {
        var result = new ArrayList<Surface>();
        var offset = (long) dataOffset;
        for (var element = 0; element < elements; element++) {
            for (var face : faces) {
                for (var level = 0; level < mipmaps; level++) {
                    var w = Math.max(1, width >> level);
                    var h = Math.max(1, height >> level);
                    var d = Math.max(1, depth >> level);
                    var size = format.surfaceBytes(w, h);
                    for (var slice = 0; slice < d; slice++) {
                        if (offset + size > fileSize || result.size() >= maxSurfaces) {
                            if (result.isEmpty()) {
                                throw new MediaException.Malformed("the DDS file ends before its first surface");
                            }
                            return result;
                        }
                        result.add(new Surface(element, face, level, slice, w, h, offset));
                        offset += size;
                    }
                }
            }
        }
        return result;
    }

    /**
     * {@return the four-character code of a string, as stored little-endian}
     */
    static int fourCc(String code) {
        return code.charAt(0) | code.charAt(1) << 8 | code.charAt(2) << 16 | code.charAt(3) << 24;
    }

    private static Field f(int channel, int shift, int bits, Type type) {
        return new Field(channel, shift, bits, type);
    }

    /**
     * Maps a legacy four-character code, or a Direct3D 9 format number stored in its place, to a format.
     */
    private static DdsFormat fourCcFormat(int code) {
        return switch (code) {
            case 36 -> DdsFormat.plain("A16B16G16R16", 8, f(0, 0, 16, Type.UNORM), f(1, 16, 16, Type.UNORM),
                    f(2, 32, 16, Type.UNORM), f(3, 48, 16, Type.UNORM));
            case 110 -> DdsFormat.plain("Q16W16V16U16", 8, f(0, 0, 16, Type.SNORM), f(1, 16, 16, Type.SNORM),
                    f(2, 32, 16, Type.SNORM), f(3, 48, 16, Type.SNORM));
            case 111 -> DdsFormat.plain("R16F", 2, f(0, 0, 16, Type.FLOAT));
            case 112 -> DdsFormat.plain("G16R16F", 4, f(0, 0, 16, Type.FLOAT), f(1, 16, 16, Type.FLOAT));
            case 113 -> DdsFormat.plain("A16B16G16R16F", 8, f(0, 0, 16, Type.FLOAT), f(1, 16, 16, Type.FLOAT),
                    f(2, 32, 16, Type.FLOAT), f(3, 48, 16, Type.FLOAT));
            case 114 -> DdsFormat.plain("R32F", 4, f(0, 0, 32, Type.FLOAT));
            case 115 -> DdsFormat.plain("G32R32F", 8, f(0, 0, 32, Type.FLOAT), f(1, 32, 32, Type.FLOAT));
            case 116 -> DdsFormat.plain("A32B32G32R32F", 16, f(0, 0, 32, Type.FLOAT), f(1, 32, 32, Type.FLOAT),
                    f(2, 64, 32, Type.FLOAT), f(3, 96, 32, Type.FLOAT));
            default -> {
                var name = new String(new char[]{(char) (code & 0xFF), (char) ((code >>> 8) & 0xFF),
                        (char) ((code >>> 16) & 0xFF), (char) (code >>> 24)});
                yield switch (name) {
                    case "DXT1" -> DdsFormat.of(name, Kind.BC1, false);
                    case "DXT2" -> DdsFormat.of(name, Kind.BC2, false).premultiplied();
                    case "DXT3" -> DdsFormat.of(name, Kind.BC2, false);
                    case "DXT4" -> DdsFormat.of(name, Kind.BC3, false).premultiplied();
                    case "DXT5" -> DdsFormat.of(name, Kind.BC3, false);
                    case "ATI1", "BC4U" -> DdsFormat.of(name, Kind.BC4, false);
                    case "BC4S" -> DdsFormat.of(name, Kind.BC4, true);
                    case "ATI2", "BC5U" -> DdsFormat.of(name, Kind.BC5, false);
                    case "BC5S" -> DdsFormat.of(name, Kind.BC5, true);
                    case "RGBG" -> DdsFormat.of(name, Kind.RGBG, false);
                    case "GRGB" -> DdsFormat.of(name, Kind.GRGB, false);
                    default -> throw new MediaException.Unsupported("dds", "DDS pixel format "
                            + (name.chars().allMatch(c -> c >= 32 && c < 127) ? name : Integer.toString(code)));
                };
            }
        };
    }

    /**
     * Maps a legacy pixel format described by masks to a format.
     */
    private static DdsFormat masked(int flags, int bitCount, long[] masks) {
        if (bitCount != 8 && bitCount != 16 && bitCount != 24 && bitCount != 32) {
            throw new MediaException.Malformed("invalid DDS bit count " + bitCount);
        }
        var fields = new ArrayList<Field>();
        var type = (flags & DDPF_BUMPDUDV) != 0 ? Type.SNORM : Type.UNORM;
        if ((flags & (DDPF_RGB | DDPF_BUMPDUDV)) != 0) {
            for (var channel = 0; channel < 3; channel++) {
                addMask(fields, channel, masks[channel], type);
            }
        } else if ((flags & DDPF_LUMINANCE) != 0) {
            addMask(fields, 0, masks[0], Type.UNORM);
        } else if ((flags & DDPF_ALPHA) == 0) {
            throw new MediaException.Unsupported("dds", "DDS pixel format flags " + Integer.toHexString(flags));
        }
        if ((flags & (DDPF_ALPHAPIXELS | DDPF_ALPHA)) != 0) {
            addMask(fields, 3, masks[3], Type.UNORM);
        }
        if (fields.isEmpty()) {
            throw new MediaException.Malformed("the DDS pixel format has no channel mask");
        }
        return DdsFormat.plain("masks", bitCount / 8, fields.toArray(Field[]::new));
    }

    /**
     * Adds a field for a contiguous channel mask; a zero mask adds nothing.
     */
    private static void addMask(List<Field> fields, int channel, long mask, Type type) {
        if (mask == 0) {
            return;
        }
        var shift = Long.numberOfTrailingZeros(mask);
        var bits = Long.bitCount(mask);
        if (((mask >>> shift) & ((1L << bits) - 1)) != (mask >>> shift)) {
            throw new MediaException.Unsupported("dds", "non-contiguous DDS channel masks");
        }
        fields.add(new Field(channel, shift, bits, type));
    }

    /**
     * Maps a DXGI format code to a format.
     */
    private static DdsFormat dxgi(int code) {
        return switch (code) {
            case 1, 2 -> DdsFormat.plain("R32G32B32A32_FLOAT", 16, f(0, 0, 32, Type.FLOAT), f(1, 32, 32, Type.FLOAT),
                    f(2, 64, 32, Type.FLOAT), f(3, 96, 32, Type.FLOAT));
            case 3 -> DdsFormat.plain("R32G32B32A32_UINT", 16, f(0, 0, 32, Type.UINT), f(1, 32, 32, Type.UINT),
                    f(2, 64, 32, Type.UINT), f(3, 96, 32, Type.UINT));
            case 4 -> DdsFormat.plain("R32G32B32A32_SINT", 16, f(0, 0, 32, Type.SINT), f(1, 32, 32, Type.SINT),
                    f(2, 64, 32, Type.SINT), f(3, 96, 32, Type.SINT));
            case 5, 6 -> DdsFormat.plain("R32G32B32_FLOAT", 12, f(0, 0, 32, Type.FLOAT), f(1, 32, 32, Type.FLOAT),
                    f(2, 64, 32, Type.FLOAT));
            case 7 -> DdsFormat.plain("R32G32B32_UINT", 12, f(0, 0, 32, Type.UINT), f(1, 32, 32, Type.UINT),
                    f(2, 64, 32, Type.UINT));
            case 8 -> DdsFormat.plain("R32G32B32_SINT", 12, f(0, 0, 32, Type.SINT), f(1, 32, 32, Type.SINT),
                    f(2, 64, 32, Type.SINT));
            case 9, 10 -> rgba16("R16G16B16A16_FLOAT", Type.FLOAT);
            case 11 -> rgba16("R16G16B16A16_UNORM", Type.UNORM);
            case 12 -> rgba16("R16G16B16A16_UINT", Type.UINT);
            case 13 -> rgba16("R16G16B16A16_SNORM", Type.SNORM);
            case 14 -> rgba16("R16G16B16A16_SINT", Type.SINT);
            case 15, 16 -> DdsFormat.plain("R32G32_FLOAT", 8, f(0, 0, 32, Type.FLOAT), f(1, 32, 32, Type.FLOAT));
            case 17 -> DdsFormat.plain("R32G32_UINT", 8, f(0, 0, 32, Type.UINT), f(1, 32, 32, Type.UINT));
            case 18 -> DdsFormat.plain("R32G32_SINT", 8, f(0, 0, 32, Type.SINT), f(1, 32, 32, Type.SINT));
            case 23, 24 -> rgb10a2("R10G10B10A2_UNORM", Type.UNORM);
            case 25 -> rgb10a2("R10G10B10A2_UINT", Type.UINT);
            case 26 -> DdsFormat.plain("R11G11B10_FLOAT", 4, f(0, 0, 11, Type.FLOAT), f(1, 11, 11, Type.FLOAT),
                    f(2, 22, 10, Type.FLOAT));
            case 27, 28, 29 -> rgba8("R8G8B8A8_UNORM", Type.UNORM);
            case 30 -> rgba8("R8G8B8A8_UINT", Type.UINT);
            case 31 -> rgba8("R8G8B8A8_SNORM", Type.SNORM);
            case 32 -> rgba8("R8G8B8A8_SINT", Type.SINT);
            case 33, 34 -> DdsFormat.plain("R16G16_FLOAT", 4, f(0, 0, 16, Type.FLOAT), f(1, 16, 16, Type.FLOAT));
            case 35 -> two("R16G16_UNORM", 16, Type.UNORM);
            case 36 -> two("R16G16_UINT", 16, Type.UINT);
            case 37 -> two("R16G16_SNORM", 16, Type.SNORM);
            case 38 -> two("R16G16_SINT", 16, Type.SINT);
            case 39, 41 -> DdsFormat.plain("R32_FLOAT", 4, f(0, 0, 32, Type.FLOAT));
            case 42 -> DdsFormat.plain("R32_UINT", 4, f(0, 0, 32, Type.UINT));
            case 43 -> DdsFormat.plain("R32_SINT", 4, f(0, 0, 32, Type.SINT));
            case 48, 49 -> two("R8G8_UNORM", 8, Type.UNORM);
            case 50 -> two("R8G8_UINT", 8, Type.UINT);
            case 51 -> two("R8G8_SNORM", 8, Type.SNORM);
            case 52 -> two("R8G8_SINT", 8, Type.SINT);
            case 53, 54 -> DdsFormat.plain("R16_FLOAT", 2, f(0, 0, 16, Type.FLOAT));
            case 56 -> DdsFormat.plain("R16_UNORM", 2, f(0, 0, 16, Type.UNORM));
            case 57 -> DdsFormat.plain("R16_UINT", 2, f(0, 0, 16, Type.UINT));
            case 58 -> DdsFormat.plain("R16_SNORM", 2, f(0, 0, 16, Type.SNORM));
            case 59 -> DdsFormat.plain("R16_SINT", 2, f(0, 0, 16, Type.SINT));
            case 60, 61 -> DdsFormat.plain("R8_UNORM", 1, f(0, 0, 8, Type.UNORM));
            case 62 -> DdsFormat.plain("R8_UINT", 1, f(0, 0, 8, Type.UINT));
            case 63 -> DdsFormat.plain("R8_SNORM", 1, f(0, 0, 8, Type.SNORM));
            case 64 -> DdsFormat.plain("R8_SINT", 1, f(0, 0, 8, Type.SINT));
            case 65 -> DdsFormat.plain("A8_UNORM", 1, f(3, 0, 8, Type.UNORM));
            case 67 -> DdsFormat.plain("R9G9B9E5_SHAREDEXP", 4, f(0, 0, 32, Type.SHARED_EXPONENT));
            case 68 -> DdsFormat.of("R8G8_B8G8_UNORM", Kind.RGBG, false);
            case 69 -> DdsFormat.of("G8R8_G8B8_UNORM", Kind.GRGB, false);
            case 70, 71, 72 -> DdsFormat.of("BC1_UNORM", Kind.BC1, false);
            case 73, 74, 75 -> DdsFormat.of("BC2_UNORM", Kind.BC2, false);
            case 76, 77, 78 -> DdsFormat.of("BC3_UNORM", Kind.BC3, false);
            case 79, 80 -> DdsFormat.of("BC4_UNORM", Kind.BC4, false);
            case 81 -> DdsFormat.of("BC4_SNORM", Kind.BC4, true);
            case 82, 83 -> DdsFormat.of("BC5_UNORM", Kind.BC5, false);
            case 84 -> DdsFormat.of("BC5_SNORM", Kind.BC5, true);
            case 85 -> DdsFormat.plain("B5G6R5_UNORM", 2, f(2, 0, 5, Type.UNORM), f(1, 5, 6, Type.UNORM),
                    f(0, 11, 5, Type.UNORM));
            case 86 -> DdsFormat.plain("B5G5R5A1_UNORM", 2, f(2, 0, 5, Type.UNORM), f(1, 5, 5, Type.UNORM),
                    f(0, 10, 5, Type.UNORM), f(3, 15, 1, Type.UNORM));
            case 87, 90, 91 -> DdsFormat.plain("B8G8R8A8_UNORM", 4, f(2, 0, 8, Type.UNORM), f(1, 8, 8, Type.UNORM),
                    f(0, 16, 8, Type.UNORM), f(3, 24, 8, Type.UNORM));
            case 88, 92, 93 -> DdsFormat.plain("B8G8R8X8_UNORM", 4, f(2, 0, 8, Type.UNORM), f(1, 8, 8, Type.UNORM),
                    f(0, 16, 8, Type.UNORM));
            case 89 -> DdsFormat.plain("R10G10B10_XR_BIAS_A2_UNORM", 4, f(0, 0, 10, Type.BIASED),
                    f(1, 10, 10, Type.BIASED), f(2, 20, 10, Type.BIASED), f(3, 30, 2, Type.UNORM));
            case 94, 95 -> DdsFormat.of("BC6H_UF16", Kind.BC6H, false);
            case 96 -> DdsFormat.of("BC6H_SF16", Kind.BC6H, true);
            case 97, 98, 99 -> DdsFormat.of("BC7_UNORM", Kind.BC7, false);
            case 115 -> DdsFormat.plain("B4G4R4A4_UNORM", 2, f(2, 0, 4, Type.UNORM), f(1, 4, 4, Type.UNORM),
                    f(0, 8, 4, Type.UNORM), f(3, 12, 4, Type.UNORM));
            // TODO: decode the depth-stencil, R1_UNORM, palette (P8, A8P8, AI44, IA44) and video (AYUV, Y410, Y416,
            // NV12, P010, P016, YUY2, Y210, Y216, NV11) DXGI formats
            default -> throw new MediaException.Unsupported("dds", "DXGI format " + code);
        };
    }

    private static DdsFormat rgba16(String name, Type type) {
        return DdsFormat.plain(name, 8, f(0, 0, 16, type), f(1, 16, 16, type), f(2, 32, 16, type), f(3, 48, 16, type));
    }

    private static DdsFormat rgba8(String name, Type type) {
        return DdsFormat.plain(name, 4, f(0, 0, 8, type), f(1, 8, 8, type), f(2, 16, 8, type), f(3, 24, 8, type));
    }

    private static DdsFormat rgb10a2(String name, Type type) {
        return DdsFormat.plain(name, 4, f(0, 0, 10, type), f(1, 10, 10, type), f(2, 20, 10, type),
                f(3, 30, 2, type));
    }

    private static DdsFormat two(String name, int bits, Type type) {
        return DdsFormat.plain(name, bits / 4, f(0, 0, bits, type), f(1, bits, bits, type));
    }
}
