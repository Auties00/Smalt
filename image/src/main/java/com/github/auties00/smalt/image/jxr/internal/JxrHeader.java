/* Ports jxrlib 1.1 (image/decode/strdec.c: ReadWMIHeader, ReadImagePlaneHeader, readQuantizerSB). Copyright (c)
   Microsoft Corporation; BSD 2-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxr.internal;

import com.github.auties00.smalt.MediaException;

/**
 * Holds the image header of a JPEG XR codestream ({@code IMAGE_HEADER} of ITU-T T.832 clause 8.3) and the header of
 * its first image plane.
 */
public final class JxrHeader {
    /** Internal color format: luminance only. */
    public static final int Y_ONLY = 0;

    /** Internal color format: YUV with chroma halved in both directions. */
    public static final int YUV_420 = 1;

    /** Internal color format: YUV with chroma halved horizontally. */
    public static final int YUV_422 = 2;

    /** Internal color format: YUV at full resolution. */
    public static final int YUV_444 = 3;

    /** Internal color format: CMYK. */
    public static final int CMYK = 4;

    /** Internal color format: independent channels. */
    public static final int NCOMPONENT = 6;

    /** Output color format: RGB. */
    public static final int RGB = 7;

    /** Output color format: RGB with a shared exponent. */
    public static final int RGBE = 8;

    /** Output bit depth: 1-bit black and white. */
    public static final int BD_1 = 0;

    /** Output bit depth: 8-bit unsigned. */
    public static final int BD_8 = 1;

    /** Output bit depth: 16-bit unsigned. */
    public static final int BD_16 = 2;

    /** Output bit depth: 16-bit signed fixed point. */
    public static final int BD_16S = 3;

    /** Output bit depth: 16-bit floating point. */
    public static final int BD_16F = 4;

    /** Output bit depth: 32-bit unsigned. */
    public static final int BD_32 = 5;

    /** Output bit depth: 32-bit signed fixed point. */
    public static final int BD_32S = 6;

    /** Output bit depth: 32-bit floating point. */
    public static final int BD_32F = 7;

    /** Output bit depth: 5 bits per RGB channel. */
    public static final int BD_5 = 8;

    /** Output bit depth: 10 bits per RGB channel. */
    public static final int BD_10 = 9;

    /** Output bit depth: 5, 6 and 5 bits of R, G and B. */
    public static final int BD_565 = 10;

    /** Subbands: every band. */
    static final int SB_ALL = 0;

    /** Subbands: no flexbits. */
    static final int SB_NO_FLEXBITS = 1;

    /** Subbands: no highpass. */
    static final int SB_NO_HIGHPASS = 2;

    /** Subbands: DC only. */
    static final int SB_DC_ONLY = 3;

    /**
     * The codec subversion of the original transform operators.
     */
    static final int SUBVERSION = 0;

    /**
     * The codec subversion of the altered operators with hard tile boundaries.
     */
    static final int SUBVERSION_HARD_TILES = 9;

    /** The codec subversion. */
    int subversion;

    /** Whether tiles do not share overlap filtering across their boundaries. */
    boolean hardTiles;

    /** Whether the bands are stored in frequency order. */
    boolean frequency;

    /** The orientation, 0 to 7. */
    int orientation;

    /** Whether an index table is present. */
    boolean indexTable;

    /** The overlap mode: 0 none, 1 one stage, 2 two stages. */
    int overlap;

    /** Whether packet headers carry trimmed flexbits. */
    boolean trimFlexbits;

    /** Whether red and blue are swapped in the 5, 565 and 10-bit formats. */
    boolean redBlueSwapped;

    /** Whether an alpha image plane follows the first one. */
    boolean alpha;

    /** The output color format. */
    int outputColorFormat;

    /** The output bit depth. */
    int outputBitDepth;

    /** Whether a 1-bit image has black as foreground. */
    boolean blackWhite;

    /** The width in pixels, without the window. */
    int width;

    /** The height in pixels, without the window. */
    int height;

    /** The window rows above the image. */
    int extraTop;

    /** The window columns left of the image. */
    int extraLeft;

    /** The window rows below the image. */
    int extraBottom;

    /** The window columns right of the image. */
    int extraRight;

    /** The number of tile columns minus one. */
    int tileColumnsMinus1;

    /** The number of tile rows minus one. */
    int tileRowsMinus1;

    /** The first macroblock column of each tile column. */
    int[] tileX = new int[4096];

    /** The first macroblock row of each tile row. */
    int[] tileY = new int[4096];

    /** The header of the first image plane. */
    Plane plane;

    /** The index of the first byte after the headers. */
    int headerEnd;

    /**
     * Holds an image plane header ({@code IMAGE_PLANE_HEADER} of clause 8.4).
     */
    static final class Plane {
        /** The internal color format. */
        int colorFormat;

        /** Whether the transform keeps extra precision bits (the scaled arithmetic of lossless coding). */
        boolean scaled;

        /** The bands stored. */
        int subband;

        /** The number of channels. */
        int channels;

        /** The shift or mantissa length of 16 and 32-bit output. */
        int mantissa;

        /** The exponent bias of floating-point output. */
        int exponentBias;

        /** The quantization flags: bits 0 to 2 for non-uniform DC, LP and HP, then the channel modes. */
        int qpMode;

        /** The uniform DC quantizer index of each channel. */
        final int[] qpDc = new int[16];

        /** The uniform LP quantizer index of each channel. */
        final int[] qpLp = new int[16];

        /** The uniform HP quantizer index of each channel. */
        final int[] qpHp = new int[16];
    }

    /**
     * Parses the image header and the first image plane header.
     *
     * @param data the bytes
     * @param start the index of the codestream's {@code "WMPHOTO"} signature
     * @param end the index one past the codestream
     * @return the header
     * @throws MediaException.Malformed if the header is invalid
     * @throws MediaException.Unsupported if the header uses a codec version this decoder does not know
     */
    public static JxrHeader parse(byte[] data, int start, int end) {
        if (end - start < 16 || !new String(data, start, 7, java.nio.charset.StandardCharsets.ISO_8859_1)
                .equals("WMPHOTO") || data[start + 7] != 0) {
            throw new MediaException.Malformed("the JPEG XR codestream has no WMPHOTO signature");
        }
        var bits = new JxrBits(data, start + 8, end);
        var header = new JxrHeader();
        if (bits.read(4) != 1) {
            throw new MediaException.Unsupported("jxr", "codec version other than 1");
        }
        header.subversion = bits.read(4);
        if (header.subversion != SUBVERSION && header.subversion != 1 && header.subversion != SUBVERSION_HARD_TILES) {
            throw new MediaException.Unsupported("jxr", "codec subversion " + header.subversion);
        }
        header.hardTiles = header.subversion == SUBVERSION_HARD_TILES;
        var tiling = bits.bit() != 0;
        header.frequency = bits.bit() != 0;
        header.orientation = bits.read(3);
        header.indexTable = bits.bit() != 0;
        header.overlap = bits.read(2);
        if (header.overlap == 3) {
            throw new MediaException.Malformed("invalid JPEG XR overlap mode 3");
        }
        var abbreviated = bits.bit() != 0;
        bits.bit();
        var inscribed = bits.bit() != 0;
        header.trimFlexbits = bits.bit() != 0;
        var stretch = bits.bit() != 0;
        header.redBlueSwapped = bits.bit() != 0;
        bits.bit();
        header.alpha = bits.bit() != 0;
        header.outputColorFormat = bits.read(4);
        header.outputBitDepth = bits.read(4);
        if (header.outputBitDepth == 0xF) {
            header.outputBitDepth = BD_1;
            header.blackWhite = true;
        }
        if (header.outputColorFormat > RGBE || header.outputColorFormat == 5 || header.outputBitDepth > BD_565) {
            throw new MediaException.Malformed("invalid JPEG XR output format " + header.outputColorFormat + "/"
                    + header.outputBitDepth);
        }
        var sizeBits = abbreviated ? 16 : 32;
        var width = (bits.read(sizeBits) & 0xFFFFFFFFL) + 1;
        var height = (bits.read(sizeBits) & 0xFFFFFFFFL) + 1;
        if (width > Integer.MAX_VALUE - 32 || height > Integer.MAX_VALUE - 32) {
            throw new MediaException.LimitExceeded("a JPEG XR image of " + width + "x" + height);
        }
        header.width = (int) width;
        header.height = (int) height;
        if (!inscribed && (header.width & 0xF) != 0) {
            header.extraRight = 0x10 - (header.width & 0xF);
        }
        if (!inscribed && (header.height & 0xF) != 0) {
            header.extraBottom = 0x10 - (header.height & 0xF);
        }
        if (tiling) {
            header.tileColumnsMinus1 = bits.read(12);
            header.tileRowsMinus1 = bits.read(12);
        }
        if (!header.indexTable && (header.frequency || header.tileColumnsMinus1 + header.tileRowsMinus1 > 0)) {
            throw new MediaException.Malformed("a JPEG XR codestream with tiles or frequency order needs an index"
                    + " table");
        }
        var tileBits = abbreviated ? 8 : 16;
        for (var index = 0; index < header.tileColumnsMinus1; index++) {
            header.tileX[index + 1] = bits.read(tileBits) + header.tileX[index];
        }
        for (var index = 0; index < header.tileRowsMinus1; index++) {
            header.tileY[index + 1] = bits.read(tileBits) + header.tileY[index];
        }
        if (stretch) {
            for (var index = 0; index < (header.tileColumnsMinus1 + 1) * (header.tileRowsMinus1 + 1); index++) {
                bits.read(8);
            }
        }
        if (inscribed) {
            header.extraTop = bits.read(6);
            header.extraLeft = bits.read(6);
            header.extraBottom = bits.read(6);
            header.extraRight = bits.read(6);
        }
        if (((header.width + header.extraLeft + header.extraRight) & 0xF)
                + ((header.height + header.extraTop + header.extraBottom) & 0xF) != 0) {
            if ((header.width & 0xF) + (header.height & 0xF) + header.extraLeft + header.extraTop != 0
                    || header.width <= header.extraRight || header.height <= header.extraBottom) {
                throw new MediaException.Malformed("invalid JPEG XR image window");
            }
            header.width -= header.extraRight;
            header.height -= header.extraBottom;
        }
        bits.alignToByte();
        header.plane = readPlane(bits, header.outputBitDepth);
        if ((header.outputBitDepth == BD_5 || header.outputBitDepth == BD_10 || header.outputBitDepth == BD_565)
                && header.plane.colorFormat != YUV_444 && header.plane.colorFormat != YUV_422
                && header.plane.colorFormat != YUV_420 && header.plane.colorFormat != Y_ONLY) {
            throw new MediaException.Malformed("a JPEG XR packed RGB image of another internal color format");
        }
        if (bits.overrun()) {
            throw new MediaException.Malformed("truncated JPEG XR image header");
        }
        header.headerEnd = (int) bits.bytePosition();
        return header;
    }

    /**
     * Reads an image plane header.
     *
     * @param bits the bit-stream at the header
     * @param bitDepth the output bit depth
     * @return the plane header
     * @throws MediaException.Malformed if the header is invalid
     */
    static Plane readPlane(JxrBits bits, int bitDepth) {
        var plane = new Plane();
        plane.colorFormat = bits.read(3);
        if (plane.colorFormat > NCOMPONENT || plane.colorFormat == 5) {
            throw new MediaException.Malformed("invalid JPEG XR internal color format " + plane.colorFormat);
        }
        plane.scaled = bits.bit() != 0;
        plane.subband = bits.read(4);
        if (plane.subband > SB_DC_ONLY) {
            throw new MediaException.Malformed("invalid JPEG XR band selection " + plane.subband);
        }
        switch (plane.colorFormat) {
            case Y_ONLY -> plane.channels = 1;
            case YUV_420 -> {
                plane.channels = 3;
                bits.read(8);
            }
            case YUV_422 -> {
                plane.channels = 3;
                bits.read(8);
            }
            case YUV_444 -> {
                plane.channels = 3;
                bits.read(8);
            }
            case NCOMPONENT -> {
                plane.channels = bits.read(4) + 1;
                bits.read(4);
            }
            default -> plane.channels = 4;
        }
        switch (bitDepth) {
            case BD_16, BD_16S, BD_32, BD_32S -> plane.mantissa = bits.read(8);
            case BD_32F -> {
                plane.mantissa = bits.read(8);
                plane.exponentBias = (byte) bits.read(8);
            }
            default -> {
            }
        }
        plane.qpMode = 0;
        if (bits.bit() == 1) {
            plane.qpMode += readQuantizers(plane.qpDc, bits, plane.channels) << 3;
        } else {
            plane.qpMode++;
        }
        if (plane.subband != SB_DC_ONLY) {
            if (bits.bit() == 0) {
                plane.qpMode += 0x200;
                if (bits.bit() == 1) {
                    plane.qpMode += readQuantizers(plane.qpLp, bits, plane.channels) << 5;
                } else {
                    plane.qpMode += 2;
                }
            } else {
                plane.qpMode += ((plane.qpMode & 1) << 1) + ((plane.qpMode & 0x18) << 2);
            }
            if (plane.subband != SB_NO_HIGHPASS) {
                if (bits.bit() == 0) {
                    plane.qpMode += 0x400;
                    if (bits.bit() == 1) {
                        plane.qpMode += readQuantizers(plane.qpHp, bits, plane.channels) << 7;
                    } else {
                        plane.qpMode += 4;
                    }
                } else {
                    plane.qpMode += ((plane.qpMode & 2) << 1) + ((plane.qpMode & 0x60) << 2);
                }
            }
        }
        if (plane.subband == SB_DC_ONLY) {
            plane.qpMode |= 0x200;
        } else if (plane.subband == SB_NO_HIGHPASS) {
            plane.qpMode |= 0x400;
        }
        if ((plane.qpMode & 0x600) == 0) {
            throw new MediaException.Malformed("a JPEG XR image plane without frame-level quantizers");
        }
        bits.alignToByte();
        return plane;
    }

    /**
     * Reads the uniform quantizer indices of every channel.
     *
     * @return the channel mode: 0 uniform, 1 mixed, 2 independent
     */
    private static int readQuantizers(int[] indices, JxrBits bits, int channels) {
        var mode = 0;
        if (channels > 1) {
            mode = bits.read(2);
        }
        indices[0] = bits.read(8);
        if (mode == 1) {
            indices[1] = bits.read(8);
        } else if (mode > 0) {
            for (var index = 1; index < channels; index++) {
                indices[index] = bits.read(8);
            }
        }
        return mode;
    }

    /**
     * {@return the width in pixels}
     */
    public int width() {
        return width;
    }

    /**
     * {@return the height in pixels}
     */
    public int height() {
        return height;
    }

    /**
     * {@return the output color format}
     */
    public int outputColorFormat() {
        return outputColorFormat;
    }

    /**
     * {@return the output bit depth}
     */
    public int outputBitDepth() {
        return outputBitDepth;
    }

    /**
     * {@return the orientation, 0 to 7}
     */
    public int orientation() {
        return orientation;
    }

    /**
     * {@return whether an alpha image plane follows the first one}
     */
    public boolean alpha() {
        return alpha;
    }

    /**
     * {@return the internal color format of the first image plane}
     */
    public int internalColorFormat() {
        return plane.colorFormat;
    }

    /**
     * {@return the number of channels of the first image plane}
     */
    public int channels() {
        return plane.channels;
    }
}
