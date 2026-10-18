package com.github.auties00.smalt.image.gif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.gif.internal.GifEncoderCore;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSink;

import java.util.Objects;

/**
 * Encodes pictures as still GIF89a images.
 *
 * <p>A picture with at most 256 distinct colors (255 when it has transparent pixels) is coded
 * exactly. Any other picture is quantized to {@link GifEncoderOptions#maxColors()} colors with
 * {@link GifEncoderOptions#method()} and mapped with {@link GifEncoderOptions#dithering()}.
 * Pixels whose alpha is below {@link GifEncoderOptions#alphaThreshold()} are coded transparent;
 * GIF has no partial transparency. The palette is the global color table, and the image data
 * is LZW-compressed exactly as giflib compresses it. Animations are encoded with
 * {@link GifAnimationEncoder}.
 *
 * <p>This class is thread-safe.
 */
public final class GifEncoder {
    /**
     * Prevents instantiation.
     */
    private GifEncoder() {
        throw new AssertionError();
    }

    /**
     * Encodes a picture as a GIF image with the {@link GifEncoderOptions#defaults()}.
     *
     * @param picture the pixels to encode
     * @return the complete GIF datastream
     * @throws MediaException.Unsupported if a planar picture's color matrix has no conversion to
     * R'G'B'
     * @throws MediaException.LimitExceeded if a dimension exceeds 65535, the picture has more than
     * {@link Integer#MAX_VALUE} pixels, or encoding runs out of memory
     * @see #encode(Picture, GifEncoderOptions)
     */
    public static byte[] encode(Picture picture) {
        return encode(picture, GifEncoderOptions.defaults());
    }

    /**
     * Encodes a picture as a GIF image and writes it to a sink, from its position.
     *
     * <p>The bytes written are those {@link #encode(Picture, GifEncoderOptions)} returns.
     *
     * @param picture the pixels to encode
     * @param options the palette, dithering, transparency and lossless settings
     * @param sink the output, written from its position; not closed
     * @throws MediaException.Unsupported if lossless coding is requested and the picture cannot be
     * coded exactly, or a planar picture's color matrix has no conversion to R'G'B'
     * @throws MediaException.LimitExceeded if a dimension exceeds 65535, the picture has more than
     * {@link Integer#MAX_VALUE} pixels, or encoding runs out of memory
     * @throws MediaException.Io if writing the sink fails
     */
    public static void encode(Picture picture, GifEncoderOptions options, MediaSink sink) {
        Objects.requireNonNull(sink, "sink");
        sink.write(encode(picture, options));
    }

    /**
     * Encodes a picture as a GIF image.
     *
     * <p>An {@link RgbaPicture} is encoded as it is; its alpha samples are used only when
     * {@link RgbaPicture#hasAlpha()} is set. Any other picture is first converted with
     * {@link ImageOps#toRgba(Picture)}: a {@link YuvPicture} or {@link Yuv16Picture} through its
     * color matrix, a {@link Rgba16Picture} reduced to 8 bits. The loop count of the options is
     * ignored.
     *
     * @param picture the pixels to encode
     * @param options the palette, dithering, transparency and lossless settings
     * @return the complete GIF datastream
     * @throws MediaException.Unsupported if lossless coding is requested and the picture has
     * translucent pixels or too many colors, or a planar picture's color matrix has no conversion
     * to R'G'B'
     * @throws MediaException.LimitExceeded if a dimension exceeds 65535, the picture has more than
     * {@link Integer#MAX_VALUE} pixels, or encoding runs out of memory
     */
    public static byte[] encode(Picture picture, GifEncoderOptions options) {
        Objects.requireNonNull(picture, "picture");
        Objects.requireNonNull(options, "options");
        var rgba = picture instanceof RgbaPicture direct ? direct : ImageOps.toRgba(picture);
        checkDimensions(rgba.width(), rgba.height());
        try (var _ = ParallelBands.operation()) {
            return Guards.encoding("GIF image", () -> {
                var core = new GifEncoderCore(rgba.width(), rgba.height(), options);
                core.add(rgba.pixels(), rgba.stride(), rgba.hasAlpha(), 0);
                return core.finish();
            });
        }
    }

    /**
     * Checks that a canvas fits the 16-bit dimensions of a GIF and the canvas buffers of the
     * encoder.
     *
     * @param width the canvas width in pixels
     * @param height the canvas height in pixels
     * @throws MediaException.LimitExceeded if a dimension exceeds 65535 or the canvas has more
     * than {@link Integer#MAX_VALUE} pixels
     */
    static void checkDimensions(int width, int height) {
        if (width > GifEncoderCore.MAX_DIMENSION || height > GifEncoderCore.MAX_DIMENSION) {
            throw new MediaException.LimitExceeded("GIF dimensions are at most " + GifEncoderCore.MAX_DIMENSION
                    + ", not " + width + "x" + height);
        }
        if ((long) width * height > Integer.MAX_VALUE) {
            throw new MediaException.LimitExceeded("a GIF canvas of " + width + "x" + height
                    + " has more pixels than the encoder holds");
        }
    }
}
