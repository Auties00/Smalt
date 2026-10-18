package com.github.auties00.smalt.image.jp2;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds a decoded JPEG 2000 image as channels of samples, before any conversion to pixels.
 *
 * <p>The channels are those of the JP2 file: a palette is already expanded and channel
 * definitions already order the color channels; for a raw codestream they are its components.
 *
 * @param x0          the left edge of the image on the reference grid at the decoded resolution
 * @param y0          the top edge of the image on the reference grid at the decoded resolution
 * @param width       the width of the image at the decoded resolution
 * @param height      the height of the image at the decoded resolution
 * @param fullWidth   the width of the image at full resolution
 * @param fullHeight  the height of the image at full resolution
 * @param reduction   the number of resolution levels discarded: the image is
 *                    {@code 2^reduction} times smaller than at full resolution, rounded up
 * @param channels    the channels
 * @param colorSpace  the declared color space
 * @param iccProfile  the embedded ICC profile
 * @param exif        the EXIF block of the file, a TIFF structure starting with its byte-order
 *                    mark
 * @param xmp         the XMP packet of the file
 */
public record Jp2Image(int x0, int y0, int width, int height, int fullWidth, int fullHeight, int reduction, List<Jp2Channel> channels,
                       Jp2ColorSpace colorSpace, Optional<byte[]> iccProfile, Optional<byte[]> exif,
                       Optional<byte[]> xmp) {
    /**
     * Validates the image and makes the channel list unmodifiable.
     *
     * @throws NullPointerException     if a reference argument is {@code null}
     * @throws IllegalArgumentException if a dimension is not positive or there is no channel
     */
    public Jp2Image {
        Objects.requireNonNull(colorSpace, "colorSpace");
        Objects.requireNonNull(iccProfile, "iccProfile");
        Objects.requireNonNull(exif, "exif");
        Objects.requireNonNull(xmp, "xmp");
        channels = List.copyOf(channels);
        if (x0 < 0 || y0 < 0 || width <= 0 || height <= 0 || fullWidth <= 0 || fullHeight <= 0 || reduction < 0) {
            throw new IllegalArgumentException("invalid image size " + width + "x" + height);
        }
        if (channels.isEmpty()) {
            throw new IllegalArgumentException("an image needs at least one channel");
        }
    }
}
