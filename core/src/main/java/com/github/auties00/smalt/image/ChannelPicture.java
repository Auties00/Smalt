package com.github.auties00.smalt.image;

/**
 * Holds a picture as packed samples of any {@link PixelLayout}: CMYK and multi-ink separations, gray or RGB
 * with extra channels, and floating-point samples.
 *
 * <p>Each pixel occupies {@link #channelCount()} consecutive samples in the order of {@link #layout()}; pixel
 * {@code (x, y)} starts at index {@code y * stride() + x * channelCount()} of the sample array. The sample type
 * is that of the implementation:
 * <ul>
 *   <li>{@link Channel8Picture}: unsigned 8-bit samples, 0 to 255;</li>
 *   <li>{@link Channel16Picture}: unsigned 16-bit samples, 0 to 65535;</li>
 *   <li>{@link ChannelFloatPicture}: 32-bit floating-point samples, nominally 0.0 to 1.0.</li>
 * </ul>
 * In every case the largest nominal value is full intensity: white for gray and RGB, full coverage for an
 * ink, full opacity for alpha; zero is black, no ink, or fully transparent. Ink channels therefore follow the
 * ICC device convention, not the inverted one some JPEG files store. Color channels are in the color space
 * their producer declares (for a decoded image, {@link DecodedImage#colorProfile()}), gamma-encoded or linear as
 * that space defines; without a declaration RGB and gray are taken as sRGB, and inks as uncalibrated device
 * values.
 *
 * <p>Decoders return a channel picture only when {@link ImageDecoderOptions#keepChannels()} asks for one.
 * {@link com.github.auties00.smalt.image.ops.ImageOps ImageOps} converts it to and from the other
 * representations and lists the operations that keep it.
 *
 * <p>Instances are not thread-safe; the sample array is shared, not copied, and threads that share a picture
 * while one of them writes its samples must synchronize externally.
 */
public sealed interface ChannelPicture extends Picture permits Channel8Picture, Channel16Picture, ChannelFloatPicture {
    /**
     * {@return the channels of every pixel, in sample order}
     */
    PixelLayout layout();

    /**
     * {@return the number of samples per pixel, {@code layout().count()}}
     */
    int channelCount();

    /**
     * Returns the distance in samples between the starts of consecutive rows.
     *
     * @return the stride, at least {@code width() * channelCount()}
     */
    int stride();

    /**
     * {@inheritDoc}
     *
     * @return whether the layout has an alpha channel, straight or premultiplied
     */
    @Override
    boolean hasAlpha();

    /**
     * {@inheritDoc}
     *
     * @return {@code 8} for {@link Channel8Picture}, {@code 16} for {@link Channel16Picture} and {@code 32} for
     * {@link ChannelFloatPicture}
     */
    @Override
    int bitDepth();
}
