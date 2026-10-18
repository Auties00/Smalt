package com.github.auties00.smalt.image;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.color.ColorProfile;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds the result of decoding a still image, or the first frame of an animated one.
 *
 * <p>The {@link #picture()} holds the stored pixels; the {@link #orientation()} still has to be
 * applied to display them upright. When the decoder used reduced-size decoding, the picture is
 * smaller than {@link #sourceWidth()} by {@link #sourceHeight()}.
 *
 * <p>{@link #iccProfile()} and {@link #colorProfile()} answer different questions. The first holds
 * the ICC profile bytes embedded in the file, exactly as stored, and is empty when the file embeds
 * none. The second is the color space of the pixels as the file declares it: the embedded ICC
 * profile when it parses, or for PNG the profile its {@code cICP}, {@code sRGB}, {@code gAMA} and
 * {@code cHRM} chunks describe; when it is empty the pixels are taken as sRGB. A file can therefore
 * have a color profile and no ICC bytes, and ICC bytes that do not parse leave the color profile
 * empty. {@link com.github.auties00.smalt.image.ops.ImageOps#toSrgb(DecodedImage)} converts the
 * pixels to sRGB, and so do the decoders when {@link ImageDecoderOptions#convertToSrgb()} is set.
 *
 * <p>{@link #xmp()} holds the XMP packet embedded in the file, its UTF-8 XML as stored (a HEIF item
 * stored compressed is inflated), and {@link com.github.auties00.smalt.metadata.XmpPacket#parse(byte[])}
 * reads it. A JPEG packet whose properties continue in Extended XMP segments is the main packet with
 * the extended properties merged into it, serialized again. The JPEG, PNG, WebP, TIFF, JPEG 2000,
 * JPEG XL, HEIC and AVIF decoders fill it; it is empty for the other formats.
 *
 * <p>Two results are equal when they hold the same picture instance, the same components, and ICC,
 * EXIF and XMP bytes of equal content; the picture is compared by identity because pictures do not
 * define value equality.
 *
 * <p>Instances are not thread-safe. The picture and the byte arrays are shared, not copied: the
 * caller owns them, and threads that share an instance while one of them modifies them must
 * synchronize externally.
 *
 * @param picture the decoded pixels, in the decoder's native representation
 * @param format the format decoded
 * @param orientation the display orientation, {@link Orientation#NORMAL} when absent
 * @param sourceWidth the stored width of the full-size image, positive
 * @param sourceHeight the stored height of the full-size image, positive
 * @param iccProfile the embedded ICC profile bytes, or empty when the file embeds none
 * @param exif the embedded EXIF block (a TIFF structure starting with its byte-order mark), or
 * empty when absent
 * @param xmp the embedded XMP packet, UTF-8 XML, or empty when absent
 * @param colorProfile the color space of the pixels the file declares, or empty when it declares
 * none (sRGB)
 */
public record DecodedImage(Picture picture, ImageFormat format, Orientation orientation, int sourceWidth,
                           int sourceHeight, Optional<byte[]> iccProfile, Optional<byte[]> exif,
                           Optional<byte[]> xmp, Optional<ColorProfile> colorProfile) {
    /**
     * Validates the result.
     *
     * @throws IllegalArgumentException if a source dimension is not positive
     */
    public DecodedImage {
        Objects.requireNonNull(picture, "picture");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(orientation, "orientation");
        Objects.requireNonNull(iccProfile, "iccProfile");
        Objects.requireNonNull(exif, "exif");
        Objects.requireNonNull(xmp, "xmp");
        Objects.requireNonNull(colorProfile, "colorProfile");
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            throw new IllegalArgumentException("invalid source size " + sourceWidth + "x" + sourceHeight);
        }
    }

    /**
     * Constructs a result without an XMP packet.
     *
     * @param picture the decoded pixels
     * @param format the format decoded
     * @param orientation the display orientation
     * @param sourceWidth the stored width of the full-size image, positive
     * @param sourceHeight the stored height of the full-size image, positive
     * @param iccProfile the embedded ICC profile bytes, or empty
     * @param exif the embedded EXIF block, or empty
     * @param colorProfile the color space of the pixels the file declares, or empty (sRGB)
     * @throws IllegalArgumentException if a source dimension is not positive
     */
    public DecodedImage(Picture picture, ImageFormat format, Orientation orientation, int sourceWidth,
                        int sourceHeight, Optional<byte[]> iccProfile, Optional<byte[]> exif,
                        Optional<ColorProfile> colorProfile) {
        this(picture, format, orientation, sourceWidth, sourceHeight, iccProfile, exif, Optional.empty(),
                colorProfile);
    }

    /**
     * Constructs a result without an XMP packet whose color profile is parsed from the embedded ICC
     * profile.
     *
     * <p>An ICC profile whose header or tag directory is invalid leaves {@link #colorProfile()}
     * empty; its bytes are still returned by {@link #iccProfile()}.
     *
     * @param picture the decoded pixels
     * @param format the format decoded
     * @param orientation the display orientation
     * @param sourceWidth the stored width of the full-size image, positive
     * @param sourceHeight the stored height of the full-size image, positive
     * @param iccProfile the embedded ICC profile bytes, or empty
     * @param exif the embedded EXIF block, or empty
     * @throws IllegalArgumentException if a source dimension is not positive
     */
    public DecodedImage(Picture picture, ImageFormat format, Orientation orientation, int sourceWidth,
                        int sourceHeight, Optional<byte[]> iccProfile, Optional<byte[]> exif) {
        this(picture, format, orientation, sourceWidth, sourceHeight, iccProfile, exif, Optional.empty(),
                parse(iccProfile));
    }

    /**
     * Returns a copy of this result with another XMP packet.
     *
     * @param xmp the embedded XMP packet, UTF-8 XML, or empty for none
     * @return the copy, which shares the picture and the other byte arrays with this result
     */
    public DecodedImage withXmp(Optional<byte[]> xmp) {
        return new DecodedImage(picture, format, orientation, sourceWidth, sourceHeight, iccProfile, exif, xmp,
                colorProfile);
    }

    /**
     * Parses an embedded ICC profile leniently.
     *
     * @param iccProfile the profile bytes, or empty
     * @return the profile, or empty when absent or invalid
     */
    private static Optional<ColorProfile> parse(Optional<byte[]> iccProfile) {
        Objects.requireNonNull(iccProfile, "iccProfile");
        if (iccProfile.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ColorProfile.parse(iccProfile.get()));
        } catch (MediaException exception) {
            return Optional.empty();
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DecodedImage that
                && picture == that.picture
                && format == that.format
                && orientation == that.orientation
                && sourceWidth == that.sourceWidth
                && sourceHeight == that.sourceHeight
                && sameBytes(iccProfile, that.iccProfile)
                && sameBytes(exif, that.exif)
                && sameBytes(xmp, that.xmp)
                && colorProfile.equals(that.colorProfile);
    }

    @Override
    public int hashCode() {
        var result = System.identityHashCode(picture);
        result = 31 * result + format.hashCode();
        result = 31 * result + orientation.hashCode();
        result = 31 * result + sourceWidth;
        result = 31 * result + sourceHeight;
        result = 31 * result + iccProfile.map(Arrays::hashCode).orElse(0);
        result = 31 * result + exif.map(Arrays::hashCode).orElse(0);
        result = 31 * result + xmp.map(Arrays::hashCode).orElse(0);
        return 31 * result + colorProfile.hashCode();
    }

    /**
     * Compares two optional byte arrays by content.
     *
     * @param first one array, or empty
     * @param second the other array, or empty
     * @return whether both are empty or both hold arrays of equal content
     */
    private static boolean sameBytes(Optional<byte[]> first, Optional<byte[]> second) {
        if (first.isEmpty() || second.isEmpty()) {
            return first.isEmpty() && second.isEmpty();
        }
        return Arrays.equals(first.get(), second.get());
    }
}
