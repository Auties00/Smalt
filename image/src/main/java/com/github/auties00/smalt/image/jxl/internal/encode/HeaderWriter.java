/* Ports libjxl 0.11.2 (lib/jxl/headers.cc, image_metadata.cc, color_encoding_internal.cc, frame_header.cc,
   loop_filter.cc and enc_toc.cc: the VisitFields orders of the codestream headers). Copyright the JPEG XL Project
   Authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.encode;

import com.github.auties00.smalt.image.jxl.internal.header.BitDepth;
import com.github.auties00.smalt.image.jxl.internal.header.ColorEncoding;
import com.github.auties00.smalt.image.jxl.internal.header.ExtraChannelInfo;
import com.github.auties00.smalt.image.jxl.internal.header.ImageHeader;

import java.nio.charset.StandardCharsets;

/**
 * Writes the codestream headers of ISO/IEC 18181-1 clauses A and C.
 */
public final class HeaderWriter {
    private HeaderWriter() {
        throw new AssertionError();
    }

    /**
     * Writes the codestream signature, the size header and the image metadata.
     *
     * @param w the output
     * @param h the header; its upsampling weights and opsin inverse are written only when they differ from the
     * defaults, which this writer never does, so they must be the defaults
     */
    public static void writeImageHeader(JxlBitWriter w, ImageHeader h) {
        w.write(16, 0x0AFF);
        writeSize(w, h.width(), h.height());
        var extraFields = h.orientation() != 1 || h.intrinsicWidth() != 0 || h.previewWidth() != 0
                || h.animation() != null || !isDefaultTone(h.toneMapping());
        var allDefault = !extraFields && !h.bitDepth().floatSample() && h.bitDepth().bitsPerSample() == 8
                && h.modular16Bit() && h.extraChannels().isEmpty() && h.xybEncoded() && isSrgb(h.colorEncoding());
        w.writeBool(allDefault);
        if (!allDefault) {
            w.writeBool(extraFields);
            if (extraFields) {
                w.write(3, h.orientation() - 1);
                w.writeBool(h.intrinsicWidth() != 0);
                if (h.intrinsicWidth() != 0) {
                    writeSize(w, h.intrinsicWidth(), h.intrinsicHeight());
                }
                w.writeBool(h.previewWidth() != 0);
                if (h.previewWidth() != 0) {
                    writePreviewSize(w, h.previewWidth(), h.previewHeight());
                }
                w.writeBool(h.animation() != null);
                if (h.animation() != null) {
                    var a = h.animation();
                    w.writeU32(a.tpsNumerator(), 100, 0, 1000, 0, 1, 10, 1, 30);
                    w.writeU32(a.tpsDenominator(), 1, 0, 1001, 0, 1, 8, 1, 10);
                    w.writeU32(a.numLoops(), 0, 0, 0, 3, 0, 16, 0, 32);
                    w.writeBool(a.haveTimecodes());
                }
            }
            writeBitDepth(w, h.bitDepth());
            w.writeBool(h.modular16Bit());
            w.writeU32(h.extraChannels().size(), 0, 0, 1, 0, 2, 4, 1, 12);
            for (var ec : h.extraChannels()) {
                writeExtraChannel(w, ec);
            }
            w.writeBool(h.xybEncoded());
            writeColorEncoding(w, h.colorEncoding());
            if (extraFields) {
                var t = h.toneMapping();
                var toneDefault = isDefaultTone(t);
                w.writeBool(toneDefault);
                if (!toneDefault) {
                    w.writeF16(t.intensityTarget());
                    w.writeF16(t.minNits());
                    w.writeBool(t.relativeToMaxDisplay());
                    w.writeF16(t.linearBelow());
                }
            }
            w.writeU64(0);
        }
        w.writeBool(true);
    }

    private static boolean isDefaultTone(ImageHeader.ToneMapping t) {
        return t.intensityTarget() == 255 && t.minNits() == 0 && !t.relativeToMaxDisplay() && t.linearBelow() == 0;
    }

    private static boolean isSrgb(ColorEncoding c) {
        return !c.wantIcc() && c.colorSpace() == ColorEncoding.RGB && c.whitePoint() == ColorEncoding.WHITE_D65
                && c.primaries() == ColorEncoding.PRIMARIES_SRGB && c.transfer() == ColorEncoding.TF_SRGB
                && c.renderingIntent() == 1;
    }

    /**
     * Writes a size header.
     *
     * @param w the output
     * @param width the width, at least 1
     * @param height the height, at least 1
     */
    static void writeSize(JxlBitWriter w, int width, int height) {
        var ratio = ratioOf(width, height);
        var small = height % 8 == 0 && height >= 8 && height <= 256
                && (ratio != 0 || (width % 8 == 0 && width >= 8 && width <= 256));
        w.writeBool(small);
        if (small) {
            w.write(5, height / 8 - 1);
        } else {
            w.writeU32(height, 1, 9, 1, 13, 1, 18, 1, 30);
        }
        w.write(3, ratio);
        if (ratio == 0) {
            if (small) {
                w.write(5, width / 8 - 1);
            } else {
                w.writeU32(width, 1, 9, 1, 13, 1, 18, 1, 30);
            }
        }
    }

    private static int ratioOf(int width, int height) {
        long h = height;
        long[] widths = {0, h, h * 12 / 10, h * 4 / 3, h * 3 / 2, h * 16 / 9, h * 5 / 4, h * 2};
        for (var r = 1; r < 8; r++) {
            if (widths[r] == width) {
                return r;
            }
        }
        return 0;
    }

    private static void writePreviewSize(JxlBitWriter w, int width, int height) {
        var div8 = height % 8 == 0 && width % 8 == 0;
        w.writeBool(div8);
        if (div8) {
            w.writeU32(height / 8, 16, 0, 32, 0, 1, 5, 33, 9);
        } else {
            w.writeU32(height, 1, 6, 65, 8, 321, 10, 1345, 12);
        }
        var ratio = ratioOf(width, height);
        w.write(3, ratio);
        if (ratio == 0) {
            if (div8) {
                w.writeU32(width / 8, 16, 0, 32, 0, 1, 5, 33, 9);
            } else {
                w.writeU32(width, 1, 6, 65, 8, 321, 10, 1345, 12);
            }
        }
    }

    /**
     * Writes a bit depth bundle.
     *
     * @param w the output
     * @param d the bit depth
     */
    static void writeBitDepth(JxlBitWriter w, BitDepth d) {
        w.writeBool(d.floatSample());
        if (d.floatSample()) {
            w.writeU32(d.bitsPerSample(), 32, 0, 16, 0, 24, 0, 1, 6);
            w.write(4, d.expBits() - 1);
        } else {
            w.writeU32(d.bitsPerSample(), 8, 0, 10, 0, 12, 0, 1, 6);
        }
    }

    /**
     * Writes an extra channel info bundle.
     *
     * @param w the output
     * @param ec the channel description
     */
    static void writeExtraChannel(JxlBitWriter w, ExtraChannelInfo ec) {
        var allDefault = ec.type() == ExtraChannelInfo.TYPE_ALPHA && !ec.bitDepth().floatSample()
                && ec.bitDepth().bitsPerSample() == 8 && ec.dimShift() == 0 && ec.name().isEmpty()
                && !ec.alphaAssociated();
        w.writeBool(allDefault);
        if (allDefault) {
            return;
        }
        w.writeEnum(ec.type());
        writeBitDepth(w, ec.bitDepth());
        w.writeU32(ec.dimShift(), 0, 0, 3, 0, 4, 0, 1, 3);
        writeName(w, ec.name());
        switch (ec.type()) {
            case ExtraChannelInfo.TYPE_ALPHA -> w.writeBool(ec.alphaAssociated());
            case ExtraChannelInfo.TYPE_SPOT_COLOR -> {
                for (var i = 0; i < 4; i++) {
                    w.writeF16(ec.spotColor()[i]);
                }
            }
            case ExtraChannelInfo.TYPE_CFA -> w.writeU32(ec.cfaChannel(), 1, 0, 0, 2, 3, 4, 19, 8);
            default -> {
            }
        }
    }

    /**
     * Writes a name string.
     *
     * @param w the output
     * @param name the name, at most 1071 UTF-8 bytes
     */
    static void writeName(JxlBitWriter w, String name) {
        var bytes = name.getBytes(StandardCharsets.UTF_8);
        w.writeU32(bytes.length, 0, 0, 0, 4, 16, 5, 48, 10);
        for (var b : bytes) {
            w.write(8, b & 0xFF);
        }
    }

    /**
     * Writes a color encoding bundle.
     *
     * @param w the output
     * @param c the color encoding
     */
    public static void writeColorEncoding(JxlBitWriter w, ColorEncoding c) {
        var allDefault = isSrgb(c);
        w.writeBool(allDefault);
        if (allDefault) {
            return;
        }
        w.writeBool(c.wantIcc());
        w.writeEnum(c.colorSpace());
        if (c.wantIcc()) {
            return;
        }
        if (c.colorSpace() != ColorEncoding.XYB) {
            w.writeEnum(c.whitePoint());
            if (c.whitePoint() == ColorEncoding.WHITE_CUSTOM) {
                writeCustomXy(w, c.white()[0], c.white()[1]);
            }
        }
        if (c.colorSpace() != ColorEncoding.XYB && c.colorSpace() != ColorEncoding.GRAY) {
            w.writeEnum(c.primaries());
            if (c.primaries() == ColorEncoding.PRIMARIES_CUSTOM) {
                var p = c.customPrimaries();
                for (var i = 0; i < 3; i++) {
                    writeCustomXy(w, p[2 * i], p[2 * i + 1]);
                }
            }
        }
        var haveGamma = c.transfer() == ColorEncoding.TF_GAMMA;
        w.writeBool(haveGamma);
        if (haveGamma) {
            w.write(24, c.gamma());
        } else {
            w.writeEnum(c.transfer());
        }
        w.writeEnum(c.renderingIntent());
    }

    private static void writeCustomXy(JxlBitWriter w, double x, double y) {
        w.writeU32(Tokens.packSigned((int) Math.round(x * 1e6)), 0, 19, 524288, 19, 1048576, 20, 2097152, 21);
        w.writeU32(Tokens.packSigned((int) Math.round(y * 1e6)), 0, 19, 524288, 19, 1048576, 20, 2097152, 21);
    }

    /**
     * The fields of a frame header the encoder writes.
     */
    public static final class Frame {
        /**
         * The frame type: 0 regular, 1 LF frame, 2 reference only, 3 skip progressive.
         */
        public int frameType;

        /**
         * Whether the frame is modular rather than VarDCT.
         */
        public boolean modular;

        /**
         * The frame flags.
         */
        public long flags;

        /**
         * Whether a non-XYB frame is YCbCr.
         */
        public boolean doYCbCr;

        /**
         * The JPEG chroma subsampling modes of the three channels.
         */
        public int[] jpegUpsampling = new int[3];

        /**
         * The color upsampling factor.
         */
        public int upsampling = 1;

        /**
         * The upsampling factor of each extra channel, or {@code null} for all 1.
         */
        public int[] ecUpsampling;

        /**
         * The modular group size shift.
         */
        public int groupSizeShift = 1;

        /**
         * The X channel quantization matrix scale of an XYB VarDCT frame.
         */
        public int xQmScale = 3;

        /**
         * The B channel quantization matrix scale of an XYB VarDCT frame.
         */
        public int bQmScale = 2;

        /**
         * The number of passes.
         */
        public int numPasses = 1;

        /**
         * The coefficient shift of each pass but the last.
         */
        public int[] passShift = new int[0];

        /**
         * The downsampling factors after which a pass is complete.
         */
        public int[] downsample = new int[0];

        /**
         * The last pass of each downsampling factor.
         */
        public int[] lastPass = new int[0];

        /**
         * The LF level of an LF frame, from 1 to 4.
         */
        public int lfLevel = 1;

        /**
         * Whether the frame has its own size and origin.
         */
        public boolean haveCrop;

        /**
         * The origin and size of a cropped frame.
         */
        public int x0;

        /**
         * The vertical origin.
         */
        public int y0;

        /**
         * The width.
         */
        public int width;

        /**
         * The height.
         */
        public int height;

        /**
         * The blend mode of the color channels.
         */
        public int blendMode;

        /**
         * The blend source reference slot.
         */
        public int blendSource;

        /**
         * The extra channel used as alpha for blending.
         */
        public int blendAlpha;

        /**
         * Whether blending clamps.
         */
        public boolean blendClamp;

        /**
         * The blend mode of each extra channel, or {@code null} to follow the color channels.
         */
        public int[] ecBlendMode;

        /**
         * The duration in ticks.
         */
        public int duration;

        /**
         * Whether this is the last frame.
         */
        public boolean isLast = true;

        /**
         * The reference slot the frame is saved in.
         */
        public int saveAsReference;

        /**
         * Whether the frame is saved before the color transform.
         */
        public boolean saveBeforeCt;

        /**
         * The frame name.
         */
        public String name = "";

        /**
         * Whether the Gaborish filter runs.
         */
        public boolean gaborish = true;

        /**
         * The number of edge-preserving filter iterations, from 0 to 3.
         */
        public int epfIterations = 2;

        /**
         * Constructs a frame with the default fields.
         */
        public Frame() {
        }
    }

    /**
     * Writes a frame header.
     *
     * @param w the output
     * @param f the frame fields
     * @param image the image header
     */
    public static void writeFrameHeader(JxlBitWriter w, Frame f, ImageHeader image) {
        var numEc = image.extraChannels().size();
        var xyb = image.xybEncoded();
        w.writeBool(false);
        w.write(2, f.frameType);
        w.writeBool(f.modular);
        w.writeU64(f.flags);
        if (!xyb) {
            w.writeBool(f.doYCbCr);
        }
        var useLf = (f.flags & 0x20) != 0;
        if (f.doYCbCr && !useLf) {
            for (var i = 0; i < 3; i++) {
                w.write(2, f.jpegUpsampling[i]);
            }
        }
        if (!useLf) {
            w.writeU32(f.upsampling, 1, 0, 2, 0, 4, 0, 8, 0);
            for (var i = 0; i < numEc; i++) {
                w.writeU32(f.ecUpsampling == null ? 1 : f.ecUpsampling[i], 1, 0, 2, 0, 4, 0, 8, 0);
            }
        }
        if (f.modular) {
            w.write(2, f.groupSizeShift);
        }
        if (xyb && !f.modular) {
            w.write(3, f.xQmScale);
            w.write(3, f.bQmScale);
        }
        if (f.frameType != 2) {
            w.writeU32(f.numPasses, 1, 0, 2, 0, 3, 0, 4, 3);
            if (f.numPasses != 1) {
                w.writeU32(f.downsample.length, 0, 0, 1, 0, 2, 0, 3, 1);
                for (var i = 0; i < f.numPasses - 1; i++) {
                    w.write(2, f.passShift[i]);
                }
                for (var d : f.downsample) {
                    w.writeU32(d, 1, 0, 2, 0, 4, 0, 8, 0);
                }
                for (var p : f.lastPass) {
                    w.writeU32(p, 0, 0, 1, 0, 2, 0, 0, 3);
                }
            }
        }
        if (f.frameType == 1) {
            w.write(2, f.lfLevel - 1);
        }
        var partial = false;
        var normal = f.frameType == 0 || f.frameType == 3;
        if (f.frameType != 1) {
            w.writeBool(f.haveCrop);
            if (f.haveCrop) {
                if (normal) {
                    w.writeU32(Tokens.packSigned(f.x0), 0, 8, 256, 11, 2304, 14, 18688, 30);
                    w.writeU32(Tokens.packSigned(f.y0), 0, 8, 256, 11, 2304, 14, 18688, 30);
                    partial = f.x0 > 0 || f.y0 > 0 || (long) f.width + f.x0 < image.width()
                            || (long) f.height + f.y0 < image.height();
                }
                w.writeU32(f.width, 0, 8, 256, 11, 2304, 14, 18688, 30);
                w.writeU32(f.height, 0, 8, 256, 11, 2304, 14, 18688, 30);
            }
        }
        if (normal) {
            writeBlending(w, f.blendMode, f.blendAlpha, f.blendClamp, f.blendSource, numEc, partial);
            for (var i = 0; i < numEc; i++) {
                var mode = f.ecBlendMode == null ? f.blendMode : f.ecBlendMode[i];
                writeBlending(w, mode, f.blendAlpha, f.blendClamp, f.blendSource, numEc, partial);
            }
            if (image.animation() != null) {
                w.writeU32(f.duration, 0, 0, 1, 0, 0, 8, 0, 32);
                if (image.animation().haveTimecodes()) {
                    w.write(32, 0);
                }
            }
            w.writeBool(f.isLast);
        }
        var isLast = normal && f.isLast;
        if (f.frameType != 1 && !isLast) {
            w.write(2, f.saveAsReference);
        }
        var canReference = !isLast && (f.duration == 0 || f.saveAsReference != 0) && f.frameType != 1;
        if (f.frameType != 1) {
            if (canReference && f.blendMode == 0 && !partial && normal) {
                w.writeBool(f.saveBeforeCt);
            } else if (f.frameType == 2) {
                w.writeBool(f.saveBeforeCt);
            }
        }
        writeName(w, f.name);
        var filterDefault = f.gaborish && f.epfIterations == 2;
        w.writeBool(filterDefault);
        if (!filterDefault) {
            w.writeBool(f.gaborish);
            if (f.gaborish) {
                w.writeBool(false);
            }
            w.write(2, f.epfIterations);
            if (f.epfIterations > 0) {
                if (!f.modular) {
                    w.writeBool(false);
                }
                w.writeBool(false);
                w.writeBool(false);
                if (f.modular) {
                    w.writeF16(1.0f);
                }
            }
            w.writeU64(0);
        }
        w.writeU64(0);
    }

    private static void writeBlending(JxlBitWriter w, int mode, int alpha, boolean clamp, int source, int numEc,
                                      boolean partial) {
        w.writeU32(mode, 0, 0, 1, 0, 2, 0, 3, 2);
        var withAlpha = numEc > 0 && (mode == 2 || mode == 3);
        if (withAlpha) {
            w.writeU32(alpha, 0, 0, 1, 0, 2, 0, 3, 3);
        }
        if (withAlpha || mode == 4) {
            w.writeBool(clamp);
        }
        if (mode != 0 || partial) {
            w.write(2, source);
        }
    }

    /**
     * Writes a table of contents without a permutation, then aligns to a byte.
     *
     * @param w the output, byte-aligned or not
     * @param sizes the byte size of each section
     */
    public static void writeToc(JxlBitWriter w, int[] sizes) {
        w.writeBool(false);
        w.zeroPadToByte();
        for (var size : sizes) {
            w.writeU32(size, 0, 10, 1024, 14, 17408, 22, 4211712, 30);
        }
        w.zeroPadToByte();
    }
}
