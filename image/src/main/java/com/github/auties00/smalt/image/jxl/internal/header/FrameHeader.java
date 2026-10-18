/* Ports jxl-oxide 0.12.6 (crates/jxl-frame: FrameHeader) and libjxl (lib/jxl/frame_header.cc:
   FrameHeader::VisitFields). Copyright Wonwoo Choi and the jxl-oxide contributors, and the JPEG XL Project Authors;
   MIT License and BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.header;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlBitReader;

import java.util.Arrays;
import java.util.TreeMap;

/**
 * Holds the header of one frame of a JPEG XL codestream (ISO/IEC 18181-1 section C.2,
 * {@code FrameHeader}), with the derived group geometry.
 *
 * <p>Instances are immutable.
 *
 * @implNote This implementation takes the field order and the conditions under which a field is
 * present from libjxl, and the derived values from jxl-oxide.
 */
public final class FrameHeader {
    /**
     * A regular frame.
     */
    public static final int REGULAR = 0;

    /**
     * An LF frame, holding the low-frequency image of a later frame.
     */
    public static final int LF_FRAME = 1;

    /**
     * A frame that is only referenced by later frames, never displayed.
     */
    public static final int REFERENCE_ONLY = 2;

    /**
     * A regular frame that is skipped by progressive decoding.
     */
    public static final int SKIP_PROGRESSIVE = 3;

    /**
     * The VarDCT encoding.
     */
    public static final int VARDCT = 0;

    /**
     * The modular encoding.
     */
    public static final int MODULAR = 1;

    /**
     * The flag that enables noise synthesis.
     */
    public static final long FLAG_NOISE = 0x1;

    /**
     * The flag that enables patches.
     */
    public static final long FLAG_PATCHES = 0x2;

    /**
     * The flag that enables splines.
     */
    public static final long FLAG_SPLINES = 0x10;

    /**
     * The flag that takes the LF image from an LF frame.
     */
    public static final long FLAG_USE_LF_FRAME = 0x20;

    /**
     * The flag that disables adaptive LF smoothing.
     */
    public static final long FLAG_SKIP_ADAPTIVE_LF_SMOOTHING = 0x80;

    /**
     * Replaces the canvas with the frame.
     */
    public static final int BLEND_REPLACE = 0;

    /**
     * Adds the frame to the canvas.
     */
    public static final int BLEND_ADD = 1;

    /**
     * Alpha-blends the frame over the canvas.
     */
    public static final int BLEND_BLEND = 2;

    /**
     * Adds the frame weighted by its alpha to the canvas.
     */
    public static final int BLEND_MULADD = 3;

    /**
     * Multiplies the canvas by the frame.
     */
    public static final int BLEND_MUL = 4;

    /**
     * Describes how a channel of a frame is blended onto the canvas.
     *
     * @param mode one of the {@code BLEND_} constants
     * @param alphaChannel the extra channel holding the alpha used by blending
     * @param clamp whether alpha (and multiplied samples) are clamped to {@code 0..1}
     * @param source the reference slot holding the canvas to blend onto
     */
    public record BlendingInfo(int mode, int alphaChannel, boolean clamp, int source) {
        /**
         * The default: replace, reading slot 0.
         */
        public static final BlendingInfo DEFAULT = new BlendingInfo(BLEND_REPLACE, 0, false, 0);

        /**
         * Returns whether the mode uses an alpha channel.
         *
         * @return whether the mode is {@link #BLEND_BLEND} or {@link #BLEND_MULADD}
         */
        public boolean usesAlpha() {
            return mode == BLEND_BLEND || mode == BLEND_MULADD;
        }
    }

    /**
     * Holds the parameters of the edge-preserving filter.
     *
     * @param iterations the number of filter steps, from 1 to 3
     * @param sharpLut the eight sigma multipliers indexed by sharpness
     * @param channelScale the distance weights of the three channels
     * @param quantMul the sigma multiplier of the quantization
     * @param pass0SigmaScale the sigma scale of step 0
     * @param pass2SigmaScale the sigma scale of step 2
     * @param borderSadMul the sigma scale at block borders
     * @param sigmaForModular the sigma of modular frames
     */
    public record EpfParams(int iterations, float[] sharpLut, float[] channelScale, float quantMul,
                            float pass0SigmaScale, float pass2SigmaScale, float borderSadMul,
                            float sigmaForModular) {
    }

    /**
     * The frame type, one of {@link #REGULAR}, {@link #LF_FRAME}, {@link #REFERENCE_ONLY} and
     * {@link #SKIP_PROGRESSIVE}.
     */
    public final int frameType;

    /**
     * The encoding, {@link #VARDCT} or {@link #MODULAR}.
     */
    public final int encoding;

    /**
     * The feature flags, a combination of the {@code FLAG_} constants.
     */
    public final long flags;

    /**
     * Whether the color channels hold YCbCr samples (a recompressed JPEG).
     */
    public final boolean doYCbCr;

    /**
     * The number of color channels the frame stores: 1 for a grayscale modular frame, else 3.
     */
    public final int encodedColorChannels;

    /**
     * The chroma subsampling mode of the Cb, Y and Cr channels, each from 0 to 3.
     */
    public final int[] jpegUpsampling;

    /**
     * The upsampling factor of the color channels: 1, 2, 4 or 8.
     */
    public final int upsampling;

    /**
     * The upsampling factor of each extra channel, as stored (before the channel's dimension
     * shift).
     */
    public final int[] ecUpsampling;

    /**
     * The group size is {@code 128 << groupSizeShift}.
     */
    public final int groupSizeShift;

    /**
     * The quantization matrix scale exponent of the X channel.
     */
    public final int xQmScale;

    /**
     * The quantization matrix scale exponent of the B channel.
     */
    public final int bQmScale;

    /**
     * The number of passes.
     */
    public final int numPasses;

    /**
     * The coefficient shift of each pass.
     */
    public final int[] passShift;

    /**
     * The downsampling factors after which a pass completes a resolution.
     */
    public final int[] downsample;

    /**
     * The last pass of each downsampling factor.
     */
    public final int[] lastPass;

    /**
     * The LF level of an LF frame, from 1 to 4, else 0.
     */
    public final int lfLevel;

    /**
     * Whether the frame has its own size and origin.
     */
    public final boolean haveCrop;

    /**
     * The horizontal origin of the frame on the canvas.
     */
    public final int x0;

    /**
     * The vertical origin of the frame on the canvas.
     */
    public final int y0;

    /**
     * The frame width, at full resolution.
     */
    public final int width;

    /**
     * The frame height, at full resolution.
     */
    public final int height;

    /**
     * The blending of the color channels.
     */
    public final BlendingInfo blending;

    /**
     * The blending of each extra channel.
     */
    public final BlendingInfo[] ecBlending;

    /**
     * The display duration, in animation ticks.
     */
    public final int duration;

    /**
     * Whether this is the last frame of the codestream.
     */
    public final boolean isLast;

    /**
     * The reference slot the frame is saved in.
     */
    public final int saveAsReference;

    /**
     * Whether the frame replaces the whole canvas.
     */
    public final boolean resetsCanvas;

    /**
     * Whether the saved reference is taken before the color transform.
     */
    public final boolean saveBeforeCt;

    /**
     * The frame name.
     */
    public final String name;

    /**
     * The Gaborish weights of the X, Y and B channels as {@code w1, w2} pairs, or {@code null}
     * when Gaborish is off.
     */
    public final float[] gaborWeights;

    /**
     * The edge-preserving filter parameters, or {@code null} when the filter is off.
     */
    public final EpfParams epf;

    /**
     * Whether the frame is the preview frame.
     */
    public final boolean preview;

    /**
     * Creates a header from its fields.
     *
     * @param b the fields
     */
    private FrameHeader(Builder b) {
        this.frameType = b.frameType;
        this.encoding = b.encoding;
        this.flags = b.flags;
        this.doYCbCr = b.doYCbCr;
        this.encodedColorChannels = b.encodedColorChannels;
        this.jpegUpsampling = b.jpegUpsampling;
        this.upsampling = b.upsampling;
        this.ecUpsampling = b.ecUpsampling;
        this.groupSizeShift = b.groupSizeShift;
        this.xQmScale = b.xQmScale;
        this.bQmScale = b.bQmScale;
        this.numPasses = b.numPasses;
        this.passShift = b.passShift;
        this.downsample = b.downsample;
        this.lastPass = b.lastPass;
        this.lfLevel = b.lfLevel;
        this.haveCrop = b.haveCrop;
        this.x0 = b.x0;
        this.y0 = b.y0;
        this.width = b.width;
        this.height = b.height;
        this.blending = b.blending;
        this.ecBlending = b.ecBlending;
        this.duration = b.duration;
        this.isLast = b.isLast;
        this.saveAsReference = b.saveAsReference;
        this.resetsCanvas = b.resetsCanvas;
        this.saveBeforeCt = b.saveBeforeCt;
        this.name = b.name;
        this.gaborWeights = b.gaborWeights;
        this.epf = b.epf;
        this.preview = b.preview;
    }

    /**
     * Collects the fields of a header while it is read.
     */
    private static final class Builder {
        /**
         * Creates an empty instance.
         */
        Builder() {
        }

        /**
         * The frame type.
         */
        int frameType = REGULAR;

        /**
         * The encoding.
         */
        int encoding = VARDCT;

        /**
         * The flags.
         */
        long flags;

        /**
         * Whether YCbCr is used.
         */
        boolean doYCbCr;

        /**
         * The stored color channel count.
         */
        int encodedColorChannels = 3;

        /**
         * The chroma subsampling modes.
         */
        int[] jpegUpsampling = new int[3];

        /**
         * The color upsampling.
         */
        int upsampling = 1;

        /**
         * The extra channel upsampling.
         */
        int[] ecUpsampling;

        /**
         * The group size shift.
         */
        int groupSizeShift = 1;

        /**
         * The X quantization matrix scale.
         */
        int xQmScale = 2;

        /**
         * The B quantization matrix scale.
         */
        int bQmScale = 2;

        /**
         * The pass count.
         */
        int numPasses = 1;

        /**
         * The pass shifts.
         */
        int[] passShift = {0};

        /**
         * The downsampling factors.
         */
        int[] downsample = new int[0];

        /**
         * The last passes.
         */
        int[] lastPass = new int[0];

        /**
         * The LF level.
         */
        int lfLevel;

        /**
         * Whether a crop is present.
         */
        boolean haveCrop;

        /**
         * The horizontal origin.
         */
        int x0;

        /**
         * The vertical origin.
         */
        int y0;

        /**
         * The width.
         */
        int width;

        /**
         * The height.
         */
        int height;

        /**
         * The color blending.
         */
        BlendingInfo blending = BlendingInfo.DEFAULT;

        /**
         * The extra channel blending.
         */
        BlendingInfo[] ecBlending;

        /**
         * The duration.
         */
        int duration;

        /**
         * Whether the frame is last.
         */
        boolean isLast = true;

        /**
         * The reference slot.
         */
        int saveAsReference;

        /**
         * Whether the canvas is reset.
         */
        boolean resetsCanvas;

        /**
         * Whether the reference is saved before the color transform.
         */
        boolean saveBeforeCt;

        /**
         * The name.
         */
        String name = "";

        /**
         * The Gaborish weights.
         */
        float[] gaborWeights = defaultGabor();

        /**
         * The EPF parameters.
         */
        EpfParams epf = defaultEpf();

        /**
         * Whether this is the preview frame.
         */
        boolean preview;
    }

    /**
     * Returns the default Gaborish weights.
     *
     * @return the weights of the three channels
     */
    private static float[] defaultGabor() {
        var w1 = (float) (1.1 * 0.104699568);
        var w2 = (float) (1.1 * 0.055680538);
        return new float[]{w1, w2, w1, w2, w1, w2};
    }

    /**
     * Returns the default edge-preserving filter parameters.
     *
     * @return the parameters with two iterations
     */
    private static EpfParams defaultEpf() {
        return new EpfParams(2, defaultSharpLut(), new float[]{40.0f, 5.0f, 3.5f}, 0.46f, 0.9f, 6.5f, 2.0f / 3.0f, 1.0f);
    }

    /**
     * Returns the default sharpness lookup table.
     *
     * @return the eight multipliers {@code i / 7}
     */
    private static float[] defaultSharpLut() {
        var lut = new float[8];
        for (var i = 0; i < 8; i++) {
            lut[i] = i / 7.0f;
        }
        return lut;
    }

    /**
     * Reads a frame header.
     *
     * @param br the bitstream, at the byte-aligned start of the frame
     * @param image the image header
     * @param preview whether this is the preview frame, whose default size is the preview size
     * @return the header
     * @throws MediaException.Malformed if a field is invalid
     */
    public static FrameHeader read(JxlBitReader br, ImageHeader image, boolean preview) {
        var b = new Builder();
        b.preview = preview;
        var numEc = image.extraChannels().size();
        var defaultWidth = preview ? image.previewWidth() : image.width();
        var defaultHeight = preview ? image.previewHeight() : image.height();
        b.width = defaultWidth;
        b.height = defaultHeight;
        b.ecUpsampling = new int[numEc];
        Arrays.fill(b.ecUpsampling, 1);
        b.ecBlending = new BlendingInfo[numEc];
        Arrays.fill(b.ecBlending, BlendingInfo.DEFAULT);
        var xyb = image.xybEncoded();
        var allDefault = br.readBool();
        if (!allDefault) {
            b.frameType = br.readBits(2);
            b.encoding = br.readBits(1);
            b.flags = br.readU64();
            if (!xyb) {
                b.doYCbCr = br.readBool();
            }
            var useLfFrame = (b.flags & FLAG_USE_LF_FRAME) != 0;
            if (b.doYCbCr && !useLfFrame) {
                for (var i = 0; i < 3; i++) {
                    b.jpegUpsampling[i] = br.readBits(2);
                }
            }
            if (!useLfFrame) {
                b.upsampling = br.readU32(1, 0, 2, 0, 4, 0, 8, 0);
                for (var i = 0; i < numEc; i++) {
                    b.ecUpsampling[i] = br.readU32(1, 0, 2, 0, 4, 0, 8, 0);
                }
            }
            if (b.encoding == MODULAR) {
                b.groupSizeShift = br.readBits(2);
            }
            if (xyb && b.encoding == VARDCT) {
                b.xQmScale = br.readBits(3);
                b.bQmScale = br.readBits(3);
            }
            if (b.frameType != REFERENCE_ONLY) {
                readPasses(br, b);
            }
            if (b.frameType == LF_FRAME) {
                b.lfLevel = 1 + br.readBits(2);
            }
            var partial = false;
            if (b.frameType != LF_FRAME) {
                b.haveCrop = br.readBool();
                if (b.haveCrop) {
                    if (b.frameType == REGULAR || b.frameType == SKIP_PROGRESSIVE) {
                        b.x0 = unpackSigned(br.readU32(0, 8, 256, 11, 2304, 14, 18688, 30));
                        b.y0 = unpackSigned(br.readU32(0, 8, 256, 11, 2304, 14, 18688, 30));
                    }
                    b.width = br.readU32(0, 8, 256, 11, 2304, 14, 18688, 30);
                    b.height = br.readU32(0, 8, 256, 11, 2304, 14, 18688, 30);
                    if (b.width == 0 || b.height == 0) {
                        throw new MediaException.Malformed("JPEG XL frame with zero width or height");
                    }
                    if (b.frameType == REGULAR || b.frameType == SKIP_PROGRESSIVE) {
                        partial = b.x0 > 0 || b.y0 > 0 || (long) b.width + b.x0 < defaultWidth
                                || (long) b.height + b.y0 < defaultHeight;
                    }
                }
            }
            var normal = b.frameType == REGULAR || b.frameType == SKIP_PROGRESSIVE;
            if (normal) {
                b.blending = readBlending(br, numEc, partial);
                for (var i = 0; i < numEc; i++) {
                    b.ecBlending[i] = readBlending(br, numEc, partial);
                }
                if (image.animation() != null) {
                    b.duration = br.readU32(0, 0, 1, 0, 0, 8, 0, 32);
                    if (image.animation().haveTimecodes()) {
                        br.readBits(32);
                    }
                }
                b.isLast = br.readBool();
            } else {
                b.isLast = false;
            }
            if (b.frameType != LF_FRAME && !b.isLast) {
                b.saveAsReference = br.readBits(2);
            }
            b.resetsCanvas = b.blending.mode() == BLEND_REPLACE && !partial;
            var canReference = !b.isLast && (b.duration == 0 || b.saveAsReference != 0) && b.frameType != LF_FRAME;
            if (b.frameType != LF_FRAME) {
                if (canReference && b.blending.mode() == BLEND_REPLACE && !partial && normal) {
                    b.saveBeforeCt = br.readBool();
                } else if (b.frameType == REFERENCE_ONLY) {
                    b.saveBeforeCt = br.readBool();
                } else {
                    b.saveBeforeCt = false;
                }
            } else {
                b.saveBeforeCt = true;
            }
            b.name = ExtraChannelInfo.readName(br);
            readRestorationFilter(br, b);
            Extensions.skip(br);
        } else {
            b.resetsCanvas = true;
            b.saveBeforeCt = false;
            b.xQmScale = xyb ? 3 : 2;
        }
        b.encodedColorChannels = b.encoding == MODULAR && !b.doYCbCr && !xyb && image.isGray() ? 1 : 3;
        if (b.encoding == VARDCT && xyb && allDefault) {
            b.xQmScale = 3;
        }
        validate(b, image);
        return new FrameHeader(b);
    }

    /**
     * Validates the cross-field constraints of a header.
     *
     * @param b the fields
     * @param image the image header
     * @throws MediaException.Malformed if a constraint is violated
     */
    private static void validate(Builder b, ImageHeader image) {
        if (b.width <= 0 || b.height <= 0 || b.width > 1 << 30 || b.height > 1 << 30
                || (long) b.width * b.height > 1L << 40) {
            throw new MediaException.Malformed("invalid JPEG XL frame size " + b.width + "x" + b.height);
        }
        var numEc = image.extraChannels().size();
        var allBlending = new BlendingInfo[numEc + 1];
        allBlending[0] = b.blending;
        System.arraycopy(b.ecBlending, 0, allBlending, 1, numEc);
        for (var info : allBlending) {
            if (info.usesAlpha() && numEc > 0) {
                if (info.alphaChannel() >= numEc || !image.extraChannels().get(info.alphaChannel()).isAlpha()) {
                    throw new MediaException.Malformed("blending alpha channel is not an alpha channel");
                }
            }
        }
        var colorShift = Integer.numberOfTrailingZeros(b.upsampling);
        for (var i = 0; i < numEc; i++) {
            var ecShift = Integer.numberOfTrailingZeros(b.ecUpsampling[i]);
            var dimShift = image.extraChannels().get(i).dimShift();
            if (ecShift + dimShift < colorShift) {
                throw new MediaException.Malformed("extra channel upsampling below color upsampling");
            }
            if (ecShift + dimShift > 6) {
                throw new MediaException.Malformed("extra channel upsampling too large");
            }
            if (ecShift + dimShift - colorShift > 7 + b.groupSizeShift) {
                throw new MediaException.Malformed("extra channel dimension shift too large");
            }
        }
    }

    /**
     * Reads the {@code Passes} bundle.
     *
     * @param br the bitstream
     * @param b the fields
     * @throws MediaException.Malformed if the sequences are not monotonic
     */
    private static void readPasses(JxlBitReader br, Builder b) {
        b.numPasses = br.readU32(1, 0, 2, 0, 3, 0, 4, 3);
        b.passShift = new int[b.numPasses];
        if (b.numPasses == 1) {
            return;
        }
        var numDs = br.readU32(0, 0, 1, 0, 2, 0, 3, 1);
        if (numDs > b.numPasses) {
            throw new MediaException.Malformed("more downsampling levels than passes");
        }
        for (var i = 0; i < b.numPasses - 1; i++) {
            b.passShift[i] = br.readBits(2);
        }
        b.downsample = new int[numDs];
        b.lastPass = new int[numDs];
        for (var i = 0; i < numDs; i++) {
            b.downsample[i] = br.readU32(1, 0, 2, 0, 4, 0, 8, 0);
            if (i > 0 && b.downsample[i] >= b.downsample[i - 1]) {
                throw new MediaException.Malformed("downsampling sequence not decreasing");
            }
        }
        for (var i = 0; i < numDs; i++) {
            b.lastPass[i] = br.readU32(0, 0, 1, 0, 2, 0, 0, 3);
            if (i > 0 && b.lastPass[i] <= b.lastPass[i - 1]) {
                throw new MediaException.Malformed("last pass sequence not increasing");
            }
            if (b.lastPass[i] >= b.numPasses) {
                throw new MediaException.Malformed("last pass beyond the pass count");
            }
        }
    }

    /**
     * Reads a {@code BlendingInfo} bundle.
     *
     * @param br the bitstream
     * @param numEc the number of extra channels
     * @param partial whether the frame covers only part of the canvas
     * @return the blending
     * @throws MediaException.Malformed if the mode is unknown
     */
    private static BlendingInfo readBlending(JxlBitReader br, int numEc, boolean partial) {
        var mode = br.readU32(0, 0, 1, 0, 2, 0, 3, 2);
        if (mode > BLEND_MUL) {
            throw new MediaException.Malformed("invalid blend mode " + mode);
        }
        var alpha = 0;
        var clamp = false;
        var withAlpha = numEc > 0 && (mode == BLEND_BLEND || mode == BLEND_MULADD);
        if (withAlpha) {
            alpha = br.readU32(0, 0, 1, 0, 2, 0, 3, 3);
        }
        if (withAlpha || mode == BLEND_MUL) {
            clamp = br.readBool();
        }
        var source = 0;
        if (mode != BLEND_REPLACE || partial) {
            source = br.readBits(2);
        }
        return new BlendingInfo(mode, alpha, clamp, source);
    }

    /**
     * Reads the {@code RestorationFilter} bundle.
     *
     * @param br the bitstream
     * @param b the fields
     * @throws MediaException.Malformed if a weight is degenerate
     */
    private static void readRestorationFilter(JxlBitReader br, Builder b) {
        var modular = b.encoding == MODULAR;
        if (br.readBool()) {
            b.gaborWeights = defaultGabor();
            b.epf = defaultEpf();
            return;
        }
        if (br.readBool()) {
            if (br.readBool()) {
                var weights = new float[6];
                for (var c = 0; c < 3; c++) {
                    weights[2 * c] = br.readF16();
                    weights[2 * c + 1] = br.readF16();
                    if (Math.abs(1.0f + (weights[2 * c] + weights[2 * c + 1]) * 4) < 1e-8f) {
                        throw new MediaException.Malformed("degenerate Gaborish weights");
                    }
                }
                b.gaborWeights = weights;
            } else {
                b.gaborWeights = defaultGabor();
            }
        } else {
            b.gaborWeights = null;
        }
        var iterations = br.readBits(2);
        if (iterations == 0) {
            b.epf = null;
        } else {
            var sharp = defaultSharpLut();
            if (!modular && br.readBool()) {
                for (var i = 0; i < 8; i++) {
                    sharp[i] = br.readF16();
                }
            }
            var scale = new float[]{40.0f, 5.0f, 3.5f};
            if (br.readBool()) {
                scale[0] = br.readF16();
                scale[1] = br.readF16();
                scale[2] = br.readF16();
                br.readF16();
                br.readF16();
            }
            var quantMul = 0.46f;
            var pass0 = 0.9f;
            var pass2 = 6.5f;
            var border = 2.0f / 3.0f;
            if (br.readBool()) {
                if (!modular) {
                    quantMul = br.readF16();
                }
                pass0 = br.readF16();
                pass2 = br.readF16();
                border = br.readF16();
            }
            var sigmaModular = 1.0f;
            if (modular) {
                sigmaModular = br.readF16();
                if (sigmaModular < 1e-8f) {
                    throw new MediaException.Malformed("EPF sigma for modular frames too small");
                }
            }
            b.epf = new EpfParams(iterations, sharp, scale, quantMul, pass0, pass2, border, sigmaModular);
        }
        Extensions.skip(br);
    }

    /**
     * Maps an unsigned value to a signed one ({@code UnpackSigned}).
     *
     * @param value the unsigned value
     * @return the signed value
     */
    private static int unpackSigned(int value) {
        return (value >>> 1) ^ -(value & 1);
    }

    /**
     * Returns whether a flag is set.
     *
     * @param flag one of the flag constants
     * @return whether it is set
     */
    public boolean hasFlag(long flag) {
        return (flags & flag) != 0;
    }

    /**
     * Returns whether this is a regular or skip-progressive frame.
     *
     * @return whether the frame is displayed or blended
     */
    public boolean isNormalFrame() {
        return frameType == REGULAR || frameType == SKIP_PROGRESSIVE;
    }

    /**
     * Returns whether this frame is displayed: a normal frame that is last or has a duration.
     *
     * @return whether the frame is a keyframe of the animation
     */
    public boolean isKeyframe() {
        return isNormalFrame() && (isLast || duration != 0);
    }

    /**
     * Returns whether the frame may be saved as a reference for later frames.
     *
     * @return whether the frame is saved in {@link #saveAsReference}
     */
    public boolean canReference() {
        return !isLast && (duration == 0 || saveAsReference != 0) && frameType != LF_FRAME;
    }

    /**
     * Returns the frame width at a given upsampling, accounting for the LF level.
     *
     * @param factor the upsampling factor
     * @return the width of the stored samples
     */
    public int sampleWidth(int factor) {
        var w = width;
        if (factor > 1) {
            w = (w + factor - 1) / factor;
        }
        if (lfLevel > 0) {
            var shift = 3 * lfLevel;
            w = (int) (((long) w + (1L << shift) - 1) >> shift);
        }
        return w;
    }

    /**
     * Returns the frame height at a given upsampling, accounting for the LF level.
     *
     * @param factor the upsampling factor
     * @return the height of the stored samples
     */
    public int sampleHeight(int factor) {
        var h = height;
        if (factor > 1) {
            h = (h + factor - 1) / factor;
        }
        if (lfLevel > 0) {
            var shift = 3 * lfLevel;
            h = (int) (((long) h + (1L << shift) - 1) >> shift);
        }
        return h;
    }

    /**
     * Returns the width of the stored color samples.
     *
     * @return the color sample width
     */
    public int colorWidth() {
        return sampleWidth(upsampling);
    }

    /**
     * Returns the height of the stored color samples.
     *
     * @return the color sample height
     */
    public int colorHeight() {
        return sampleHeight(upsampling);
    }

    /**
     * Returns the group dimension.
     *
     * @return {@code 128 << groupSizeShift}
     */
    public int groupDim() {
        return 128 << groupSizeShift;
    }

    /**
     * Returns the LF group dimension.
     *
     * @return eight times the group dimension
     */
    public int lfGroupDim() {
        return groupDim() * 8;
    }

    /**
     * Returns the number of groups per row.
     *
     * @return the group columns
     */
    public int groupsPerRow() {
        return ceilDiv(colorWidth(), groupDim());
    }

    /**
     * Returns the number of group rows.
     *
     * @return the group rows
     */
    public int groupRows() {
        return ceilDiv(colorHeight(), groupDim());
    }

    /**
     * Returns the number of groups.
     *
     * @return the group count
     */
    public int numGroups() {
        return groupsPerRow() * groupRows();
    }

    /**
     * Returns the number of LF groups per row.
     *
     * @return the LF group columns
     */
    public int lfGroupsPerRow() {
        return ceilDiv(colorWidth(), lfGroupDim());
    }

    /**
     * Returns the number of LF group rows.
     *
     * @return the LF group rows
     */
    public int lfGroupRows() {
        return ceilDiv(colorHeight(), lfGroupDim());
    }

    /**
     * Returns the number of LF groups.
     *
     * @return the LF group count
     */
    public int numLfGroups() {
        return lfGroupsPerRow() * lfGroupRows();
    }

    /**
     * Returns the index of the LF group that contains a group.
     *
     * @param group the group index
     * @return the LF group index
     */
    public int lfGroupOfGroup(int group) {
        var perRow = groupsPerRow();
        return (group % perRow) / 8 + (group / perRow) / 8 * lfGroupsPerRow();
    }

    /**
     * Returns the pass ranges of the downsampling levels: for every pass that completes a level,
     * the {@code [minShift, maxShift)} range of channel shifts it completes.
     *
     * @return the ranges keyed by pass index
     */
    public TreeMap<Integer, int[]> passShifts() {
        var map = new TreeMap<Integer, int[]>();
        var maxShift = 3;
        for (var i = 0; i < downsample.length; i++) {
            var minShift = Integer.numberOfTrailingZeros(downsample[i]);
            map.put(lastPass[i], new int[]{minShift, maxShift});
            maxShift = minShift;
        }
        map.put(numPasses - 1, new int[]{0, maxShift});
        return map;
    }

    /**
     * Divides rounding up.
     *
     * @param a the dividend, non-negative
     * @param b the divisor, positive
     * @return {@code ceil(a / b)}
     */
    private static int ceilDiv(int a, int b) {
        return (int) (((long) a + b - 1) / b);
    }
}
