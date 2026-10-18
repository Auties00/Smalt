/* Ports jxl-oxide 0.12.6 (crates/jxl-image: ImageHeader). Copyright Wonwoo Choi and the jxl-oxide contributors;
   MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.header;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlBitReader;

import java.util.List;

/**
 * Holds the image header of a JPEG XL codestream (ISO/IEC 18181-1 section A.2): the size and the
 * {@code ImageMetadata} bundle.
 *
 * @param width the image width in pixels, before orientation
 * @param height the image height in pixels, before orientation
 * @param orientation the EXIF orientation, from 1 to 8
 * @param intrinsicWidth the recommended display width, or 0 when absent
 * @param intrinsicHeight the recommended display height, or 0 when absent
 * @param previewWidth the width of the preview frame, or 0 when the image has none
 * @param previewHeight the height of the preview frame, or 0 when the image has none
 * @param animation the animation timing, or {@code null} for a still image
 * @param bitDepth the sample format of the color channels
 * @param modular16Bit whether 16-bit buffers suffice for every modular sample
 * @param extraChannels the extra channels
 * @param xybEncoded whether the color is stored in the XYB space
 * @param colorEncoding the color space of the image
 * @param toneMapping the HDR tone mapping metadata
 * @param opsinInverse the XYB to linear RGB parameters
 * @param upsampling2 the 15 weights of the 2x upsampling kernel
 * @param upsampling4 the 55 weights of the 4x upsampling kernel
 * @param upsampling8 the 210 weights of the 8x upsampling kernel
 */
public record ImageHeader(int width, int height, int orientation, int intrinsicWidth, int intrinsicHeight,
                          int previewWidth, int previewHeight, Animation animation, BitDepth bitDepth,
                          boolean modular16Bit, List<ExtraChannelInfo> extraChannels, boolean xybEncoded,
                          ColorEncoding colorEncoding, ToneMapping toneMapping, OpsinInverse opsinInverse,
                          float[] upsampling2, float[] upsampling4, float[] upsampling8) {
    /**
     * The codestream signature, {@code FF 0A} read as a little-endian 16-bit value.
     */
    private static final int SIGNATURE = 0x0AFF;

    /**
     * Describes the timing of an animation.
     *
     * @param tpsNumerator the numerator of the ticks per second
     * @param tpsDenominator the denominator of the ticks per second
     * @param numLoops the number of times the animation plays, 0 for forever
     * @param haveTimecodes whether frames carry SMPTE timecodes
     */
    public record Animation(int tpsNumerator, int tpsDenominator, int numLoops, boolean haveTimecodes) {
    }

    /**
     * Holds the HDR tone mapping metadata.
     *
     * @param intensityTarget the luminance, in candelas per square meter, of nominal white
     * @param minNits the lowest luminance of the content
     * @param relativeToMaxDisplay whether {@code linearBelow} is a fraction of the display peak
     * @param linearBelow the luminance below which tone mapping keeps values linear
     */
    public record ToneMapping(float intensityTarget, float minNits, boolean relativeToMaxDisplay, float linearBelow) {
        /**
         * The default metadata: a 255 cd/m2 intensity target.
         */
        public static final ToneMapping DEFAULT = new ToneMapping(255, 0, false, 0);
    }

    /**
     * Holds the parameters of the XYB to linear RGB conversion.
     *
     * @param matrix the 3x3 inverse opsin matrix, row-major
     * @param opsinBias the opsin bias of each channel
     * @param quantBias the quantization bias of each channel
     * @param quantBiasNumerator the numerator of the quantization bias of large coefficients
     */
    public record OpsinInverse(float[] matrix, float[] opsinBias, float[] quantBias, float quantBiasNumerator) {
        /**
         * The default parameters (ISO/IEC 18181-1 table A.20).
         */
        public static final OpsinInverse DEFAULT = new OpsinInverse(
                new float[]{
                        11.031566901960783f, -9.866943921568629f, -0.16462299647058826f,
                        -3.254147380392157f, 4.418770392156863f, -0.16462299647058826f,
                        -3.6588512862745097f, 2.7129230470588235f, 1.9459282392156863f},
                new float[]{-0.0037930732552754493f, -0.0037930732552754493f, -0.0037930732552754493f},
                new float[]{1.0f - 0.05465007330715401f, 1.0f - 0.07005449891748593f, 1.0f - 0.049935103337343655f},
                0.145f);
    }

    /**
     * Reads the image header.
     *
     * @param br the bitstream, at the start of the codestream
     * @return the header
     * @throws MediaException.Malformed if the signature or a field is invalid
     */
    public static ImageHeader read(JxlBitReader br) {
        if (br.readBits(16) != SIGNATURE) {
            throw new MediaException.Malformed("JPEG XL codestream signature mismatch");
        }
        var size = readSize(br);
        var width = size[0];
        var height = size[1];
        var allDefault = br.readBool();
        var extraFields = !allDefault && br.readBool();
        var orientation = 1;
        var intrinsicWidth = 0;
        var intrinsicHeight = 0;
        var previewWidth = 0;
        var previewHeight = 0;
        Animation animation = null;
        if (extraFields) {
            orientation = 1 + br.readBits(3);
            if (br.readBool()) {
                var intrinsic = readSize(br);
                intrinsicWidth = intrinsic[0];
                intrinsicHeight = intrinsic[1];
            }
            if (br.readBool()) {
                var preview = readPreviewSize(br);
                previewWidth = preview[0];
                previewHeight = preview[1];
            }
            if (br.readBool()) {
                var num = br.readU32(100, 0, 1000, 0, 1, 10, 1, 30);
                var den = br.readU32(1, 0, 1001, 0, 1, 8, 1, 10);
                var loops = br.readU32(0, 0, 0, 3, 0, 16, 0, 32);
                var timecodes = br.readBool();
                animation = new Animation(num, den, loops, timecodes);
            }
        }
        var bitDepth = BitDepth.DEFAULT;
        var modular16 = true;
        List<ExtraChannelInfo> extra = List.of();
        var xyb = true;
        var color = ColorEncoding.SRGB;
        var tone = ToneMapping.DEFAULT;
        if (!allDefault) {
            bitDepth = BitDepth.read(br);
            modular16 = br.readBool();
            var numExtra = br.readU32(0, 0, 1, 0, 2, 4, 1, 12);
            if (numExtra > 256) {
                throw new MediaException.Malformed("too many extra channels: " + numExtra);
            }
            var channels = new ExtraChannelInfo[numExtra];
            for (var i = 0; i < numExtra; i++) {
                channels[i] = ExtraChannelInfo.read(br);
            }
            extra = List.of(channels);
            xyb = br.readBool();
            color = ColorEncoding.read(br);
            if (extraFields) {
                tone = readToneMapping(br);
            }
            Extensions.skip(br);
        }
        var defaultMatrix = br.readBool();
        var opsin = OpsinInverse.DEFAULT;
        float[] up2 = UpsamplingWeights.UP2;
        float[] up4 = UpsamplingWeights.UP4;
        float[] up8 = UpsamplingWeights.UP8;
        if (!defaultMatrix) {
            if (xyb) {
                opsin = readOpsinInverse(br);
            }
            var mask = br.readBits(3);
            if ((mask & 1) != 0) {
                up2 = readF16Array(br, 15);
            }
            if ((mask & 2) != 0) {
                up4 = readF16Array(br, 55);
            }
            if ((mask & 4) != 0) {
                up8 = readF16Array(br, 210);
            }
        }
        if (!(tone.intensityTarget() > 0)) {
            throw new MediaException.Malformed("invalid intensity target");
        }
        if (tone.minNits() < 0 || tone.minNits() > tone.intensityTarget()) {
            throw new MediaException.Malformed("invalid tone mapping minimum");
        }
        if (tone.linearBelow() < 0 || (tone.relativeToMaxDisplay() && tone.linearBelow() > 1)) {
            throw new MediaException.Malformed("invalid tone mapping linear threshold");
        }
        return new ImageHeader(width, height, orientation, intrinsicWidth, intrinsicHeight, previewWidth, previewHeight,
                animation, bitDepth, modular16, extra, xyb, color, tone, opsin, up2, up4, up8);
    }

    /**
     * Reads a {@code SizeHeader}.
     *
     * @param br the bitstream
     * @return {@code {width, height}}
     */
    private static int[] readSize(JxlBitReader br) {
        var div8 = br.readBool();
        int height;
        if (div8) {
            height = 8 * (1 + br.readBits(5));
        } else {
            height = br.readU32(1, 9, 1, 13, 1, 18, 1, 30);
        }
        var ratio = br.readBits(3);
        int width;
        if (ratio != 0) {
            width = ratioWidth(ratio, height);
        } else if (div8) {
            width = 8 * (1 + br.readBits(5));
        } else {
            width = br.readU32(1, 9, 1, 13, 1, 18, 1, 30);
        }
        return new int[]{width, height};
    }

    /**
     * Reads a {@code PreviewHeader}.
     *
     * @param br the bitstream
     * @return {@code {width, height}}
     */
    private static int[] readPreviewSize(JxlBitReader br) {
        var div8 = br.readBool();
        int height;
        if (div8) {
            height = 8 * br.readU32(16, 0, 32, 0, 1, 5, 33, 9);
        } else {
            height = br.readU32(1, 6, 65, 8, 321, 10, 1345, 12);
        }
        var ratio = br.readBits(3);
        int width;
        if (ratio != 0) {
            width = ratioWidth(ratio, height);
        } else if (div8) {
            width = 8 * br.readU32(16, 0, 32, 0, 1, 5, 33, 9);
        } else {
            width = br.readU32(1, 6, 65, 8, 321, 10, 1345, 12);
        }
        return new int[]{width, height};
    }

    /**
     * Computes the width implied by an aspect ratio code.
     *
     * @param ratio the code, from 1 to 7
     * @param height the image height in pixels
     * @return the width
     */
    private static int ratioWidth(int ratio, int height) {
        var h = height & 0xFFFF_FFFFL;
        var w = switch (ratio) {
            case 1 -> h;
            case 2 -> h * 12 / 10;
            case 3 -> h * 4 / 3;
            case 4 -> h * 3 / 2;
            case 5 -> h * 16 / 9;
            case 6 -> h * 5 / 4;
            default -> h * 2;
        };
        if (w > Integer.MAX_VALUE) {
            throw new MediaException.Malformed("JPEG XL image too wide");
        }
        return (int) w;
    }

    /**
     * Reads the {@code ToneMapping} bundle.
     *
     * @param br the bitstream
     * @return the metadata
     */
    private static ToneMapping readToneMapping(JxlBitReader br) {
        if (br.readBool()) {
            return ToneMapping.DEFAULT;
        }
        var target = br.readF16();
        var min = br.readF16();
        var relative = br.readBool();
        var below = br.readF16();
        return new ToneMapping(target, min, relative, below);
    }

    /**
     * Reads the {@code OpsinInverseMatrix} bundle.
     *
     * @param br the bitstream
     * @return the parameters
     */
    private static OpsinInverse readOpsinInverse(JxlBitReader br) {
        if (br.readBool()) {
            return OpsinInverse.DEFAULT;
        }
        var matrix = readF16Array(br, 9);
        var bias = readF16Array(br, 3);
        var quantBias = readF16Array(br, 3);
        var numerator = br.readF16();
        return new OpsinInverse(matrix, bias, quantBias, numerator);
    }

    /**
     * Reads consecutive {@code F16} values.
     *
     * @param br the bitstream
     * @param count the number of values
     * @return the values
     */
    private static float[] readF16Array(JxlBitReader br, int count) {
        var values = new float[count];
        for (var i = 0; i < count; i++) {
            values[i] = br.readF16();
        }
        return values;
    }

    /**
     * Returns the index of the first alpha channel.
     *
     * @return the extra channel index, or -1 when the image has no alpha
     */
    public int alphaChannel() {
        for (var i = 0; i < extraChannels.size(); i++) {
            if (extraChannels.get(i).isAlpha()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns whether the image is grayscale.
     *
     * @return whether the color space is gray
     */
    public boolean isGray() {
        return colorEncoding.isGray();
    }

    /**
     * Returns the width after the orientation is applied.
     *
     * @return the display width
     */
    public int displayWidth() {
        return orientation > 4 ? height : width;
    }

    /**
     * Returns the height after the orientation is applied.
     *
     * @return the display height
     */
    public int displayHeight() {
        return orientation > 4 ? width : height;
    }
}
