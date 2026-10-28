package com.github.auties00.smalt.image;

import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.internal.tiff.ExifBlocks;
import com.github.auties00.smalt.internal.tiff.TiffExif;
import com.github.auties00.smalt.metadata.XmpPacket;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Holds the format-independent settings of an image encoding through {@link Images}. Instances are
 * immutable and thread-safe; create them with {@link #builder()} or a preset such as
 * {@link #defaults()}.
 *
 * <p>Besides the coding settings, the options carry the metadata the encoded file records: the color
 * profile of the pixels, an EXIF block, an XMP packet and the display orientation. Each format records
 * what it has a place for, as the table shows; what it cannot record is handled as the accessors
 * describe, so that the encoded file always displays the colors and the orientation the options state.
 *
 * <table class="striped">
 * <caption>Where each format records the metadata</caption>
 * <thead>
 * <tr><th scope="col">Format</th><th scope="col">Color profile</th><th scope="col">EXIF</th>
 * <th scope="col">XMP</th><th scope="col">Orientation</th></tr>
 * </thead>
 * <tbody>
 * <tr><td>JPEG</td><td>{@code APP2 ICC_PROFILE}</td><td>{@code APP1 Exif}</td><td>{@code APP1} XMP, with
 * Extended XMP for a large packet</td><td>EXIF</td></tr>
 * <tr><td>PNG, APNG</td><td>{@code iCCP} ({@code cICP} for HDR)</td><td>{@code eXIf}</td>
 * <td>{@code iTXt XML:com.adobe.xmp}</td><td>EXIF</td></tr>
 * <tr><td>WebP</td><td>{@code ICCP}</td><td>{@code EXIF}</td><td>{@code XMP }</td><td>EXIF</td></tr>
 * <tr><td>GIF</td><td>{@code ICCRGBG1} extension (RGB profiles)</td><td>none</td>
 * <td>{@code XMP DataXMP} extension</td><td>none</td></tr>
 * <tr><td>AVIF</td><td>{@code colr}</td><td>{@code Exif} item</td><td>{@code mime} item</td>
 * <td>{@code irot} and {@code imir}, and EXIF</td></tr>
 * <tr><td>TIFF</td><td>tag 34675</td><td>Exif and GPS directories</td><td>tag 700</td>
 * <td>{@code Orientation} tag</td></tr>
 * <tr><td>JPEG 2000</td><td>{@code colr}</td><td>{@code uuid} box</td><td>{@code xml } box</td>
 * <td>EXIF</td></tr>
 * <tr><td>BMP</td><td>V5 header</td><td>none</td><td>none</td><td>none</td></tr>
 * <tr><td>ICO, ANI</td><td>none</td><td>none</td><td>none</td><td>none</td></tr>
 * </tbody>
 * </table>
 *
 * @apiNote The coding settings are what every image format understands; a caller that needs a format's
 * other parameters uses that format's encoder directly.
 */
public final class ImageEncoderOptions {
    private static final ImageEncoderOptions DEFAULTS = new Builder().build();

    private final boolean lossless;
    private final int quality;
    private final int loopCount;
    private final ColorProfile colorProfile;
    private final byte[] exif;
    private final byte[] xmp;
    private final Orientation orientation;

    private ImageEncoderOptions(Builder builder, int quality) {
        this.lossless = builder.lossless;
        this.quality = quality;
        this.loopCount = builder.loopCount;
        this.colorProfile = builder.colorProfile;
        this.exif = builder.exif;
        this.xmp = builder.xmp;
        this.orientation = builder.orientation;
    }

    /**
     * Returns lossy coding at the format's default quality (lossless for formats that only code
     * losslessly), with animations looping forever and no metadata.
     *
     * @return the default options
     */
    public static ImageEncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns lossy coding at a quality, with animations looping forever and no metadata.
     *
     * @param quality the quality, from 1 (smallest) to 100 (best)
     * @return the options
     * @throws IllegalArgumentException if {@code quality} is outside 1..100
     */
    public static ImageEncoderOptions ofQuality(int quality) {
        return builder().quality(quality).build();
    }

    /**
     * Returns lossless coding, with animations looping forever and no metadata.
     *
     * @return the options
     */
    public static ImageEncoderOptions lossless() {
        return builder().lossless(true).build();
    }

    /**
     * Returns a builder initialized with {@link #defaults()}.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a builder initialized with these options.
     *
     * @return the builder
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * {@return whether the image is coded losslessly; {@code false} by default}
     *
     * <p>A format that is always lossless (PNG) codes losslessly whatever this says; JPEG codes a
     * lossless JPEG (ITU-T T.81 Annex H) at the picture's bit depth, an RGB picture in RGB; a format that
     * holds fewer bits per sample than the picture has (8 for WebP, GIF, ICO and BMP, 12 for AVIF, 16 for
     * JPEG) rejects a lossless request with {@link com.github.auties00.smalt.MediaException.Unsupported},
     * unless the picture's samples are exactly those of an 8-bit picture, which it then codes.
     */
    public boolean isLossless() {
        return lossless;
    }

    /**
     * {@return the lossy quality, from 1 (smallest) to 100 (best), or empty for the format's
     * default; empty by default}
     *
     * <p>Each format maps this common scale onto its own quality setting. A lossless coding ignores
     * it.
     */
    public OptionalInt quality() {
        return quality == 0 ? OptionalInt.empty() : OptionalInt.of(quality);
    }

    /**
     * {@return the number of times an animation plays, at least 0, where 0 means forever; 0 by
     * default}
     *
     * <p>An animation written through {@link Images#openAnimationWriter(ImageFormat, int, int,
     * ImageEncoderOptions, com.github.auties00.smalt.io.MediaSink)} takes its loop count from here;
     * a still image ignores it. A format that stores a smaller range rejects a larger count when
     * the writer is opened.
     */
    public int loopCount() {
        return loopCount;
    }

    /**
     * {@return the color space of the pixels handed to the encoder, or empty when they are sRGB; empty by
     * default}
     *
     * <p>A format that records a profile of this color space for the samples it writes embeds the profile
     * (its {@link ColorProfile#toByteArray() ICC bytes}, or for PNG the {@code cICP} chunk of a
     * high-dynamic-range profile) and codes the pixels unchanged. Otherwise (an ICO or ANI file, a GIF with
     * a profile that is not RGB, a PNG with a profile that is neither RGB nor gray, a gray coding with an RGB
     * profile, a CMYK picture in a format without CMYK) the encoder converts the pixels to sRGB through the
     * profile, as {@link com.github.auties00.smalt.image.ops.ImageOps#toSrgb(DecodedImage)} does, and
     * embeds none, so the colors are right either way. A CMYK profile needs a picture of the CMYK layout,
     * and an RGB or gray profile a picture of color channels that are not inks; the encoder rejects any
     * other pairing with an {@link IllegalArgumentException}.
     */
    public Optional<ColorProfile> colorProfile() {
        return Optional.ofNullable(colorProfile);
    }

    /**
     * {@return a copy of the EXIF block the file records, a TIFF structure starting with its byte-order
     * mark, or empty for none; empty by default}
     *
     * <p>The block is embedded byte for byte, so its maker note and every value keep their offsets,
     * except that its {@code Orientation} entry is set to {@link #orientation()} when that is present
     * (patched in place, or added in a new first directory when the block has none; a block whose first
     * directory cannot be read is replaced by one holding only the orientation). A format with no
     * place for EXIF (GIF, BMP, ICO, ANI) leaves it out; JPEG rejects a block of more than 65527 bytes,
     * what one {@code APP1} segment holds, with an {@link IllegalArgumentException}.
     */
    public Optional<byte[]> exif() {
        return Optional.ofNullable(exif).map(byte[]::clone);
    }

    /**
     * {@return a copy of the XMP packet the file records, serialized in UTF-8, or empty for none; empty by
     * default}
     *
     * <p>The packet is embedded as given, except that a {@code tiff:Orientation} property it holds is set
     * to {@link #orientation()} when that is present, and a JPEG packet too large for one {@code APP1}
     * segment is split into a main packet and Extended XMP segments, both of which serialize the packet
     * again with {@link XmpPacket#toBytes()}. A packet that has to be parsed and does not parse is left out:
     * by JPEG when it is too large for one segment, by TIFF and JPEG 2000, which rebuild every packet, and by
     * GIF when it holds a zero byte (a UTF-16 or UTF-32 packet), which GIF serializes again in UTF-8
     * otherwise. A format with no place for XMP (BMP, ICO, ANI) leaves it out.
     */
    public Optional<byte[]> xmp() {
        return Optional.ofNullable(xmp).map(byte[]::clone);
    }

    /**
     * {@return the display orientation the file records, or empty to record only what the
     * {@link #exif()} block says; empty by default}
     *
     * <p>The pixels are the stored pixels, and the orientation says how to turn them for display. A
     * present orientation is written into the EXIF block, which is created holding only the orientation
     * when {@link #exif()} is empty and the orientation is not {@link Orientation#NORMAL}, and into the
     * format's own orientation field (the TIFF {@code Orientation} tag, the AVIF {@code irot} and
     * {@code imir} properties). A still image in a format that records no orientation (GIF, BMP, ICO, ANI) is
     * turned upright instead, so that it displays the same; an animation in such a format
     * rejects an orientation other than {@link Orientation#NORMAL} when its writer is opened.
     */
    public Optional<Orientation> orientation() {
        return Optional.ofNullable(orientation);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ImageEncoderOptions that && lossless == that.lossless && quality == that.quality
                && loopCount == that.loopCount && Objects.equals(colorProfile, that.colorProfile)
                && Arrays.equals(exif, that.exif) && Arrays.equals(xmp, that.xmp) && orientation == that.orientation;
    }

    @Override
    public int hashCode() {
        return Objects.hash(lossless, quality, loopCount, colorProfile, Arrays.hashCode(exif), Arrays.hashCode(xmp),
                orientation);
    }

    @Override
    public String toString() {
        return "ImageEncoderOptions[lossless=" + lossless + ", quality=" + quality + ", loopCount=" + loopCount
                + (colorProfile == null ? "" : ", colorProfile=" + colorProfile)
                + (exif == null ? "" : ", exif=" + exif.length + " bytes")
                + (xmp == null ? "" : ", xmp=" + xmp.length + " bytes")
                + (orientation == null ? "" : ", orientation=" + orientation) + "]";
    }

    /**
     * Builds {@link ImageEncoderOptions}.
     *
     * <p>Setters store their value, copying arrays; {@link #build()} checks the ranges and the EXIF
     * block. Instances are not thread-safe.
     */
    public static final class Builder {
        private boolean lossless;
        private OptionalInt quality = OptionalInt.empty();
        private int loopCount;
        private ColorProfile colorProfile;
        private byte[] exif;
        private byte[] xmp;
        private Orientation orientation;

        private Builder() {
        }

        private Builder(ImageEncoderOptions options) {
            this.lossless = options.lossless;
            this.quality = options.quality();
            this.loopCount = options.loopCount;
            this.colorProfile = options.colorProfile;
            this.exif = options.exif;
            this.xmp = options.xmp;
            this.orientation = options.orientation;
        }

        /**
         * Sets whether the image is coded losslessly; see {@link ImageEncoderOptions#isLossless()}.
         *
         * @param lossless the value
         * @return this builder
         */
        public Builder lossless(boolean lossless) {
            this.lossless = lossless;
            return this;
        }

        /**
         * Sets the lossy quality; see {@link ImageEncoderOptions#quality()}.
         *
         * @param quality the value
         * @return this builder
         */
        public Builder quality(int quality) {
            this.quality = OptionalInt.of(quality);
            return this;
        }

        /**
         * Selects the format's default quality; see {@link ImageEncoderOptions#quality()}.
         *
         * @return this builder
         */
        public Builder automaticQuality() {
            this.quality = OptionalInt.empty();
            return this;
        }

        /**
         * Sets the number of times an animation plays; see {@link ImageEncoderOptions#loopCount()}.
         *
         * @param loopCount the value
         * @return this builder
         */
        public Builder loopCount(int loopCount) {
            this.loopCount = loopCount;
            return this;
        }

        /**
         * Sets the color space of the pixels; see {@link ImageEncoderOptions#colorProfile()}.
         *
         * @param colorProfile the value
         * @return this builder
         */
        public Builder colorProfile(ColorProfile colorProfile) {
            this.colorProfile = Objects.requireNonNull(colorProfile, "colorProfile");
            return this;
        }

        /**
         * Takes the pixels as sRGB and embeds no profile; see {@link ImageEncoderOptions#colorProfile()}.
         *
         * @return this builder
         */
        public Builder noColorProfile() {
            this.colorProfile = null;
            return this;
        }

        /**
         * Sets the EXIF block; see {@link ImageEncoderOptions#exif()}.
         *
         * @param exif the value, a TIFF structure with or without the {@code "Exif\0\0"} identifier JPEG
         * puts in front of it, which is removed; copied
         * @return this builder
         */
        public Builder exif(byte[] exif) {
            this.exif = TiffExif.stripExifPrefix(Objects.requireNonNull(exif, "exif")).clone();
            return this;
        }

        /**
         * Records no EXIF block; see {@link ImageEncoderOptions#exif()}.
         *
         * @return this builder
         */
        public Builder noExif() {
            this.exif = null;
            return this;
        }

        /**
         * Sets the XMP packet; see {@link ImageEncoderOptions#xmp()}.
         *
         * @param xmp the value, the packet serialized in UTF-8; copied
         * @return this builder
         */
        public Builder xmp(byte[] xmp) {
            this.xmp = Objects.requireNonNull(xmp, "xmp").clone();
            return this;
        }

        /**
         * Sets the XMP packet to the serialization of a parsed one; see {@link ImageEncoderOptions#xmp()}.
         *
         * @param xmp the value, serialized with {@link XmpPacket#toBytes()}
         * @return this builder
         */
        public Builder xmp(XmpPacket xmp) {
            this.xmp = Objects.requireNonNull(xmp, "xmp").toBytes();
            return this;
        }

        /**
         * Records no XMP packet; see {@link ImageEncoderOptions#xmp()}.
         *
         * @return this builder
         */
        public Builder noXmp() {
            this.xmp = null;
            return this;
        }

        /**
         * Sets the display orientation the file records; see {@link ImageEncoderOptions#orientation()}.
         *
         * @param orientation the value
         * @return this builder
         */
        public Builder orientation(Orientation orientation) {
            this.orientation = Objects.requireNonNull(orientation, "orientation");
            return this;
        }

        /**
         * Records only the orientation of the EXIF block; see {@link ImageEncoderOptions#orientation()}.
         *
         * @return this builder
         */
        public Builder automaticOrientation() {
            this.orientation = null;
            return this;
        }

        /**
         * Sets the color profile, EXIF block, XMP packet and orientation to those of a decoded image, so
         * that encoding its picture records what the decoded file recorded.
         *
         * <p>The color profile becomes {@link DecodedImage#colorProfile()} (none when it is empty or sRGB
         * and the file embedded no ICC bytes), the EXIF block and XMP packet those of the image or none (an
         * EXIF block that does not start with a TIFF byte-order mark, which {@link #build()} would reject,
         * is left out), and the orientation {@link DecodedImage#orientation()}, which an HEIF transform
         * property may set apart from the EXIF entry.
         *
         * @param image the decoded image whose picture is encoded
         * @return this builder
         */
        public Builder metadataOf(DecodedImage image) {
            Objects.requireNonNull(image, "image");
            var profile = image.colorProfile().orElse(null);
            this.colorProfile = profile == null || profile.isSrgb() && image.iccProfile().isEmpty() ? null : profile;
            this.exif = image.exif().map(TiffExif::stripExifPrefix).filter(ExifBlocks::isTiff).map(byte[]::clone)
                    .orElse(null);
            this.xmp = image.xmp().map(byte[]::clone).orElse(null);
            this.orientation = image.orientation();
            return this;
        }

        /**
         * Returns options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the quality is outside 1..100, the loop count is negative or
         * the EXIF block does not start with a TIFF byte-order mark
         */
        public ImageEncoderOptions build() {
            var checkedQuality = quality.isPresent() ? Checks.inRange("quality", quality.getAsInt(), 1, 100) : 0;
            Checks.nonNegative("loop count", loopCount);
            if (exif != null && !ExifBlocks.isTiff(exif)) {
                throw new IllegalArgumentException("the EXIF block does not start with a TIFF byte-order mark");
            }
            return new ImageEncoderOptions(this, checkedQuality);
        }
    }
}
