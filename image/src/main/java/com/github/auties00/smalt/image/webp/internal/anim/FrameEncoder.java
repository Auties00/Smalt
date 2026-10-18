package com.github.auties00.smalt.image.webp.internal.anim;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.webp.internal.alpha.AlphaEncoder;
import com.github.auties00.smalt.image.webp.internal.color.YuvConverter;
import com.github.auties00.smalt.image.webp.internal.lossless.Vp8lEncoder;
import com.github.auties00.smalt.image.webp.internal.riff.FourCc;
import com.github.auties00.smalt.image.webp.internal.riff.WebpWriter;
import com.github.auties00.smalt.video.vp8.internal.Vp8;
import com.github.auties00.smalt.video.vp8.internal.Vp8KeyFrameSettings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Encodes the bitstream of one WebP image or animation frame, lossless or lossy with alpha.
 */
public final class FrameEncoder {
    /**
     * The largest width or height of an encoded WebP image.
     */
    public static final int MAX_DIMENSION = 16383;

    /**
     * Prevents instantiation.
     */
    private FrameEncoder() {
        throw new AssertionError();
    }

    /**
     * Holds the chunks of an encoded image.
     *
     * @param bitstream the {@code VP8 } or {@code VP8L} payload
     * @param lossless whether the bitstream is VP8L
     * @param alph the {@code ALPH} payload of a lossy image with transparency, or {@code null}
     * @param hasAlpha whether the image has transparency
     */
    public record EncodedImage(byte[] bitstream, boolean lossless, byte[] alph, boolean hasAlpha) {
        /**
         * Returns the serialized chunks: {@code ALPH} (when present) and the bitstream.
         *
         * @return the chunks
         */
        public List<byte[]> chunks() {
            var chunks = new ArrayList<byte[]>(2);
            if (alph != null) {
                chunks.add(WebpWriter.chunk(FourCc.ALPH, alph));
            }
            chunks.add(WebpWriter.chunk(lossless ? FourCc.VP8L : FourCc.VP8, bitstream));
            return chunks;
        }

        /**
         * Returns the number of bytes of the serialized chunks.
         *
         * @return the size
         */
        public long size() {
            return WebpWriter.diskSize(bitstream.length) + (alph == null ? 0 : WebpWriter.diskSize(alph.length));
        }
    }

    /**
     * Checks that an image fits the WebP dimension limit.
     *
     * @param width the image width in pixels
     * @param height the image height in pixels
     * @throws MediaException.LimitExceeded if a side exceeds 16383 pixels
     */
    public static void checkDimensions(int width, int height) {
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new MediaException.LimitExceeded("WebP images are at most " + MAX_DIMENSION
                    + " pixels per side, got " + width + "x" + height);
        }
    }

    /**
     * Packs an RGBA region into ARGB integers.
     *
     * @param rgba the RGBA samples
     * @param offset the index of the first byte of the region
     * @param stride the distance in bytes between rows
     * @param width the region width
     * @param height the region height
     * @param useAlpha whether alpha is kept; when {@code false} every pixel is made opaque
     * @param exact whether fully transparent pixels keep their color; when {@code false}
     * they become transparent black
     * @return the ARGB pixels
     */
    public static int[] toArgb(byte[] rgba, int offset, int stride, int width, int height, boolean useAlpha, boolean exact) {
        var argb = new int[width * height];
        for (var y = 0; y < height; y++) {
            var p = offset + y * stride;
            var d = y * width;
            for (var x = 0; x < width; x++, p += 4) {
                var a = useAlpha ? rgba[p + 3] & 0xFF : 0xFF;
                var pixel = (a << 24) | (rgba[p] & 0xFF) << 16 | (rgba[p + 1] & 0xFF) << 8 | (rgba[p + 2] & 0xFF);
                argb[d + x] = (!exact && a == 0) ? 0 : pixel;
            }
        }
        return argb;
    }

    /**
     * Encodes ARGB pixels losslessly.
     *
     * @param argb the pixels
     * @param width the image width in pixels
     * @param height the image height in pixels
     * @param quality the quality (effort), from 0 to 100
     * @param method the method, from 0 to 6
     * @param exact whether transparent pixels keep their color
     * @return the encoded image
     */
    public static EncodedImage encodeLossless(int[] argb, int width, int height, int quality, int method, boolean exact) {
        var payload = Vp8lEncoder.encodeImage(argb, width, height, quality, method, exact);
        var hasAlpha = ((payload[4] & 0xFF) >>> 4 & 1) != 0;
        return new EncodedImage(payload, true, null, hasAlpha);
    }

    /**
     * Prepares a 4:2:0 picture from RGBA for lossy coding: converts with alpha-weighted chroma
     * averaging and, unless exact, smooths the color of transparent areas.
     *
     * @param rgba the RGBA samples, the first row first
     * @param stride the distance in bytes between rows
     * @param width the image width in pixels
     * @param height the image height in pixels
     * @param useAlpha whether the alpha channel is meaningful
     * @param exact whether transparent pixels keep their color
     * @param limits the resource bounds
     * @return the picture, with an alpha plane when some pixel is not opaque
     */
    public static YuvPicture prepareLossy(byte[] rgba, int stride, int width, int height, boolean useAlpha,
                                          boolean exact, MediaLimits limits) {
        var yuv = YuvConverter.fromRgba(rgba, stride, width, height, useAlpha, limits);
        if (!exact) {
            YuvConverter.cleanupTransparentArea(yuv);
        }
        return yuv;
    }

    /**
     * {@return whether a picture uses the color representation of WebP lossy coding:
     * limited-range BT.601 4:2:0}
     *
     * @param picture the picture to inspect
     */
    public static boolean isLossyLayout(YuvPicture picture) {
        var matrix = picture.color().matrix();
        return picture.chromaFormat() == ChromaFormat.YUV420
                && (matrix == MatrixCoefficients.BT601 || matrix == MatrixCoefficients.BT470BG)
                && picture.color().range() == ColorRange.LIMITED;
    }

    /**
     * Encodes the alpha plane of a prepared lossy picture.
     *
     * @param picture the prepared picture
     * @param alphaQuality the alpha quality, from 0 to 100
     * @param alphaFilter the alpha filter mode, from 0 to 2
     * @param method the encoder method
     * @return the {@code ALPH} payload, or {@code null} without an alpha plane
     */
    public static byte[] encodeAlpha(YuvPicture picture, int alphaQuality, int alphaFilter, int method) {
        var alpha = picture.alpha();
        if (alpha == null) {
            return null;
        }
        var width = picture.width();
        var height = picture.height();
        var plane = alpha;
        if (picture.alphaStride() != width) {
            plane = new byte[width * height];
            for (var y = 0; y < height; y++) {
                System.arraycopy(alpha, y * picture.alphaStride(), plane, y * width, width);
            }
        }
        return AlphaEncoder.encode(plane, width, height, alphaQuality, alphaFilter, method);
    }

    /**
     * Encodes the color of a prepared lossy picture.
     *
     * <p>The payload always has an even length: an odd bitstream gets a trailing zero byte,
     * which VP8 decoders ignore, so the chunk needs no separate padding byte.
     *
     * @implNote This implementation pads like libwebp's {@code VP8EncWrite}
     * ({@code src/enc/syntax_enc.c}), which counts the padding byte in the declared
     * {@code VP8 } chunk size; the files are then byte-identical to cwebp's.
     *
     * @param picture the picture; its alpha plane is ignored
     * @param quality the quality, from 0 to 100
     * @param method the method, from 0 to 6
     * @param disableFilter whether the loop filter is turned off (for blended animation frames)
     * @return the {@code VP8 } payload, of even length
     */
    public static byte[] encodeVp8(YuvPicture picture, float quality, int method, boolean disableFilter) {
        var opaque = YuvPicture.builder().size(picture.width(), picture.height()).chromaFormat(picture.chromaFormat())
                .color(picture.color())
                .luma(picture.y(), picture.yStride())
                .chroma(picture.u(), picture.v(), picture.uvStride())
                .build();
        var base = Vp8KeyFrameSettings.quality(quality);
        var options = new Vp8KeyFrameSettings(quality, method, base.segments(),
                disableFilter ? 0 : base.filterStrength(), base.sharpness(), base.partitionCount(), base.snsStrength());
        var payload = Vp8.encodeKeyFrame(opaque, options);
        return (payload.length & 1) == 0 ? payload : Arrays.copyOf(payload, payload.length + 1);
    }
}
