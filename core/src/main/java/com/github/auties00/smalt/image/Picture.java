package com.github.auties00.smalt.image;

/**
 * Holds the pixels of one decoded or to-be-encoded picture.
 *
 * <p>A picture is packed RGBA, planar Y'CbCr with an optional alpha plane, or packed samples of any channel
 * layout:
 * <ul>
 *   <li>{@link RgbaPicture}: packed 8-bit R, G, B, A;</li>
 *   <li>{@link Rgba16Picture}: packed 16-bit R, G, B, A;</li>
 *   <li>{@link YuvPicture}: planar 8-bit Y'CbCr;</li>
 *   <li>{@link Yuv16Picture}: planar Y'CbCr of 8 to 16 significant bits per sample;</li>
 *   <li>{@link ChannelPicture}: packed 8-bit, 16-bit or floating-point samples of a {@link PixelLayout},
 *       which holds what the others cannot: CMYK and multi-ink separations, extra channels and
 *       floating-point samples.</li>
 * </ul>
 * Decoders return the representation their format stores natively (JPEG and lossy WebP produce
 * {@link YuvPicture}, PNG, GIF and lossless WebP produce {@link RgbaPicture}), so a pipeline that
 * re-encodes to the same family never pays for a color conversion it does not need. Sources with
 * more than 8 bits per sample (16-bit PNG, 10 and 12-bit AV1 and VP9) are reduced to 8 bits by
 * default, and produce the 16-bit representations only when the caller opts in; likewise CMYK, multi-ink,
 * floating-point and extra-channel sources are converted to RGBA by default, and produce a
 * {@link ChannelPicture} only when {@link ImageDecoderOptions#keepChannels()} asks for one. Every row of every
 * plane starts at index {@code row * stride} of its array; there is no leading offset.
 *
 * <p>Pictures are mutable containers whose sample arrays are shared, not copied. A decoder that
 * recycles its buffers documents how long a picture it hands out stays valid. Instances are not
 * thread-safe; threads that share a picture while one of them writes its samples must synchronize
 * externally.
 *
 * @apiNote The set of representations is closed, so a {@code switch} over a picture can be
 *          exhaustive. Code that handles only 8-bit RGBA and Y'CbCr pictures can convert any picture with
 *          {@link com.github.auties00.smalt.image.ops.ImageOps#toRgba(Picture)} first, or reduce the
 *          16-bit ones with {@link com.github.auties00.smalt.image.ops.ImageOps#toEightBit(Picture)}.
 */
public sealed interface Picture permits RgbaPicture, Rgba16Picture, YuvPicture, Yuv16Picture, ChannelPicture {
    /**
     * Returns the width in pixels.
     *
     * @return the width, positive
     */
    int width();

    /**
     * Returns the height in pixels.
     *
     * @return the height, positive
     */
    int height();

    /**
     * Returns whether this picture carries per-pixel transparency.
     *
     * <p>For a packed RGBA picture this reports the flag set by its producer, not a scan of the
     * pixels; for a planar picture it reports whether an alpha plane is attached, and for a
     * {@link ChannelPicture} whether its layout has an alpha channel.
     *
     * @return whether alpha is present
     */
    boolean hasAlpha();

    /**
     * Returns the number of significant bits of every sample.
     *
     * @return {@code 8} for {@link RgbaPicture} and {@link YuvPicture}, {@code 16} for
     *         {@link Rgba16Picture}, the declared depth, 8 to 16, for {@link Yuv16Picture}, and 8, 16 or 32
     *         for a {@link ChannelPicture}
     */
    int bitDepth();
}
