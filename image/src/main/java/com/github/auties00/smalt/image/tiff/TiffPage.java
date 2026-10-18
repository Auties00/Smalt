package com.github.auties00.smalt.image.tiff;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.tiff.internal.TiffDirectory;
import com.github.auties00.smalt.image.tiff.internal.TiffLayout;
import com.github.auties00.smalt.image.tiff.internal.TiffTags;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Describes one page of a TIFF file: the structure and the descriptive tags of one image file
 * directory of the main chain, read without decoding pixels.
 *
 * <p>Every tag is reported as stored, defaulted as TIFF 6.0 specifies when absent. A page may use
 * a feature the decoder lacks; {@link TiffPages#read(int)} then raises
 * {@link MediaException.Unsupported}, while its description is still available here.
 *
 * <p>Instances are immutable and thread-safe; {@link #iccProfile()} returns a copy.
 */
public final class TiffPage {
    /**
     * The page index in the main chain.
     */
    private final int index;

    /**
     * The width in pixels.
     */
    private final int width;

    /**
     * The height in pixels.
     */
    private final int height;

    /**
     * The bits per sample of the first sample.
     */
    private final int bitsPerSample;

    /**
     * The samples per pixel.
     */
    private final int samplesPerPixel;

    /**
     * The sample format.
     */
    private final TiffSampleFormat sampleFormat;

    /**
     * The photometric interpretation.
     */
    private final TiffPhotometric photometric;

    /**
     * The compression scheme.
     */
    private final TiffCompression compression;

    /**
     * The predictor.
     */
    private final TiffPredictor predictor;

    /**
     * Whether samples are stored in separate planes.
     */
    private final boolean planar;

    /**
     * The tile width, or {@code 0} for strips.
     */
    private final int tileWidth;

    /**
     * The tile height, or {@code 0} for strips.
     */
    private final int tileHeight;

    /**
     * The rows per strip, or {@code 0} for tiles.
     */
    private final int rowsPerStrip;

    /**
     * Whether a sample is alpha.
     */
    private final boolean alpha;

    /**
     * Whether the alpha is associated.
     */
    private final boolean alphaPremultiplied;

    /**
     * The display orientation.
     */
    private final Orientation orientation;

    /**
     * The {@code NewSubfileType} flags.
     */
    private final long subfileType;

    /**
     * The horizontal resolution, or {@code 0} when absent.
     */
    private final double xResolution;

    /**
     * The vertical resolution, or {@code 0} when absent.
     */
    private final double yResolution;

    /**
     * The {@code ResolutionUnit} value.
     */
    private final int resolutionUnit;

    /**
     * The embedded ICC profile, or {@code null}.
     */
    private final byte[] iccProfile;

    /**
     * The {@code ImageDescription}, or {@code null}.
     */
    private final String description;

    /**
     * The {@code PageName}, or {@code null}.
     */
    private final String pageName;

    /**
     * The {@code Software}, or {@code null}.
     */
    private final String software;

    /**
     * The {@code DateTime}, or {@code null}.
     */
    private final String dateTime;

    /**
     * The {@code Make}, or {@code null}.
     */
    private final String make;

    /**
     * The {@code Model}, or {@code null}.
     */
    private final String model;

    /**
     * Reads the description of a directory.
     *
     * @param index the page index
     * @param directory the directory of the page
     * @throws MediaException.Malformed if a tag has an invalid type or truncated values
     */
    private TiffPage(int index, TiffDirectory directory) {
        this.index = index;
        this.width = (int) Math.clamp(directory.longValue(TiffTags.IMAGE_WIDTH, 0), 0, Integer.MAX_VALUE);
        this.height = (int) Math.clamp(directory.longValue(TiffTags.IMAGE_LENGTH, 0), 0, Integer.MAX_VALUE);
        this.bitsPerSample = (int) Math.clamp(directory.longValue(TiffTags.BITS_PER_SAMPLE, 1), 0, 0xFFFF);
        var layout = TiffLayout.of(directory);
        this.samplesPerPixel = layout.samples();
        this.sampleFormat = TiffSampleFormat.of((int) directory.longValue(TiffTags.SAMPLE_FORMAT, 1));
        this.photometric = TiffPhotometric.of(layout.photometric());
        this.compression = TiffCompression.of((int) directory.longValue(TiffTags.COMPRESSION, 1));
        this.predictor = TiffPredictor.of((int) directory.longValue(TiffTags.PREDICTOR, 1));
        this.planar = layout.planar();
        var tiled = directory.has(TiffTags.TILE_WIDTH);
        this.tileWidth = tiled ? (int) Math.clamp(directory.longValue(TiffTags.TILE_WIDTH, 0), 0, Integer.MAX_VALUE) : 0;
        this.tileHeight = tiled ? (int) Math.clamp(directory.longValue(TiffTags.TILE_LENGTH, 0), 0, Integer.MAX_VALUE) : 0;
        var rows = directory.longValue(TiffTags.ROWS_PER_STRIP, height);
        this.rowsPerStrip = tiled ? 0 : (int) (rows <= 0 || rows > height ? height : rows);
        this.alpha = layout.alpha() >= 0;
        this.alphaPremultiplied = layout.associated();
        this.orientation = layout.orientation();
        this.subfileType = directory.longValue(TiffTags.NEW_SUBFILE_TYPE, 0);
        this.xResolution = directory.doubleValue(TiffTags.X_RESOLUTION, 0);
        this.yResolution = directory.doubleValue(TiffTags.Y_RESOLUTION, 0);
        this.resolutionUnit = (int) directory.longValue(TiffTags.RESOLUTION_UNIT, 2);
        var profile = directory.bytes(TiffTags.ICC_PROFILE);
        this.iccProfile = profile != null && profile.length > 0 ? profile : null;
        this.description = directory.string(TiffTags.IMAGE_DESCRIPTION);
        this.pageName = directory.string(TiffTags.PAGE_NAME);
        this.software = directory.string(TiffTags.SOFTWARE);
        this.dateTime = directory.string(TiffTags.DATE_TIME);
        this.make = directory.string(TiffTags.MAKE);
        this.model = directory.string(TiffTags.MODEL);
    }

    /**
     * Reads the description of a directory.
     *
     * @param index the page index
     * @param directory the directory of the page
     * @return the page
     * @throws MediaException.Malformed if a tag has an invalid type or truncated values
     */
    static TiffPage of(int index, TiffDirectory directory) {
        return new TiffPage(index, directory);
    }

    /**
     * Returns the index of the page in the file's main chain of directories.
     *
     * @return the index, from zero
     */
    public int index() {
        return index;
    }

    /**
     * Returns the width.
     *
     * @return the {@code ImageWidth} in pixels, before the orientation is applied
     */
    public int width() {
        return width;
    }

    /**
     * Returns the height.
     *
     * @return the {@code ImageLength} in pixels, before the orientation is applied
     */
    public int height() {
        return height;
    }

    /**
     * Returns the bits per sample.
     *
     * @return the {@code BitsPerSample} of the first sample, 1 when absent
     */
    public int bitsPerSample() {
        return bitsPerSample;
    }

    /**
     * Returns the samples per pixel, extra samples included.
     *
     * @return the {@code SamplesPerPixel}, 1 when absent
     */
    public int samplesPerPixel() {
        return samplesPerPixel;
    }

    /**
     * Returns the sample format.
     *
     * @return the {@code SampleFormat} of the first sample
     */
    public TiffSampleFormat sampleFormat() {
        return sampleFormat;
    }

    /**
     * Returns the photometric interpretation.
     *
     * @return the interpretation; when the tag is absent, {@link TiffPhotometric#RGB} for three or
     * more color samples and {@link TiffPhotometric#BLACK_IS_ZERO} otherwise
     */
    public TiffPhotometric photometric() {
        return photometric;
    }

    /**
     * Returns the compression scheme.
     *
     * @return the scheme, {@link TiffCompression#NONE} when absent
     */
    public TiffCompression compression() {
        return compression;
    }

    /**
     * Returns the predictor.
     *
     * @return the predictor, {@link TiffPredictor#NONE} when absent
     */
    public TiffPredictor predictor() {
        return predictor;
    }

    /**
     * Returns whether the samples of a pixel are stored in separate planes
     * ({@code PlanarConfiguration} 2).
     *
     * @return {@code true} for separate planes
     */
    public boolean isPlanar() {
        return planar;
    }

    /**
     * Returns whether the image is stored in tiles rather than strips.
     *
     * @return {@code true} for a tiled image
     */
    public boolean isTiled() {
        return tileWidth > 0;
    }

    /**
     * Returns the tile width.
     *
     * @return the {@code TileWidth}, or empty for an image in strips
     */
    public OptionalInt tileWidth() {
        return tileWidth > 0 ? OptionalInt.of(tileWidth) : OptionalInt.empty();
    }

    /**
     * Returns the tile height.
     *
     * @return the {@code TileLength}, or empty for an image in strips
     */
    public OptionalInt tileHeight() {
        return tileWidth > 0 ? OptionalInt.of(tileHeight) : OptionalInt.empty();
    }

    /**
     * Returns the rows per strip.
     *
     * @return the {@code RowsPerStrip}, at most the height, or empty for a tiled image
     */
    public OptionalInt rowsPerStrip() {
        return tileWidth > 0 ? OptionalInt.empty() : OptionalInt.of(rowsPerStrip);
    }

    /**
     * {@return whether the pixels carry alpha}
     *
     * <p>The first extra sample is alpha when it is associated or unassociated alpha, or when it
     * is unspecified and there are more than three samples per pixel (CMYK excepted); the fourth
     * sample of an RGB image without {@code ExtraSamples} is associated alpha.
     */
    public boolean hasAlpha() {
        return alpha;
    }

    /**
     * {@return whether the alpha is stored premultiplied (associated alpha)}
     *
     * <p>The decoder divides it out: decoded pictures always carry straight alpha.
     */
    public boolean isAlphaPremultiplied() {
        return alphaPremultiplied;
    }

    /**
     * Returns the display orientation.
     *
     * @return the {@code Orientation}, {@link Orientation#NORMAL} when absent or invalid
     */
    public Orientation orientation() {
        return orientation;
    }

    /**
     * Returns whether the page is a reduced-resolution version of another page
     * ({@code NewSubfileType} bit 0), such as a thumbnail or a pyramid level.
     *
     * @return {@code true} for a reduced-resolution page
     */
    public boolean isReducedResolution() {
        return (subfileType & 1) != 0;
    }

    /**
     * Returns whether the page is a transparency mask for another page ({@code NewSubfileType}
     * bit 2).
     *
     * @return {@code true} for a mask
     */
    public boolean isMask() {
        return (subfileType & 4) != 0;
    }

    /**
     * Returns the resolution in dots per inch.
     *
     * @return the horizontal and vertical resolution, converted from centimeters when the
     * {@code ResolutionUnit} is 3, or empty when the resolution is absent or the unit is
     * 1 (no absolute unit)
     */
    public Optional<Resolution> resolution() {
        if (!(xResolution > 0) || !(yResolution > 0) || resolutionUnit == 1) {
            return Optional.empty();
        }
        var factor = resolutionUnit == 3 ? 2.54 : 1;
        return Optional.of(new Resolution(xResolution * factor, yResolution * factor));
    }

    /**
     * Holds the resolution of a page.
     *
     * @param horizontal the horizontal resolution in dots per inch, positive
     * @param vertical the vertical resolution in dots per inch, positive
     */
    public record Resolution(double horizontal, double vertical) {
    }

    /**
     * Returns the embedded ICC profile.
     *
     * @return a copy of the profile of tag 34675, or empty when absent
     */
    public Optional<byte[]> iccProfile() {
        return iccProfile == null ? Optional.empty() : Optional.of(iccProfile.clone());
    }

    /**
     * Returns the image description.
     *
     * @return the {@code ImageDescription} text, or empty when absent
     */
    public Optional<String> description() {
        return Optional.ofNullable(description);
    }

    /**
     * Returns the page name.
     *
     * @return the {@code PageName} text, or empty when absent
     */
    public Optional<String> pageName() {
        return Optional.ofNullable(pageName);
    }

    /**
     * Returns the software that wrote the page.
     *
     * @return the {@code Software} text, or empty when absent
     */
    public Optional<String> software() {
        return Optional.ofNullable(software);
    }

    /**
     * Returns the date and time the page was created.
     *
     * @return the {@code DateTime} text ({@code "YYYY:MM:DD HH:MM:SS"}), or empty when absent
     */
    public Optional<String> dateTime() {
        return Optional.ofNullable(dateTime);
    }

    /**
     * Returns the manufacturer of the device that made the page.
     *
     * @return the {@code Make} text, or empty when absent
     */
    public Optional<String> make() {
        return Optional.ofNullable(make);
    }

    /**
     * Returns the model of the device that made the page.
     *
     * @return the {@code Model} text, or empty when absent
     */
    public Optional<String> model() {
        return Optional.ofNullable(model);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TiffPage that
                && index == that.index && width == that.width && height == that.height
                && bitsPerSample == that.bitsPerSample && samplesPerPixel == that.samplesPerPixel
                && sampleFormat == that.sampleFormat && photometric == that.photometric
                && compression == that.compression && predictor == that.predictor && planar == that.planar
                && tileWidth == that.tileWidth && tileHeight == that.tileHeight && rowsPerStrip == that.rowsPerStrip
                && alpha == that.alpha
                && alphaPremultiplied == that.alphaPremultiplied && orientation == that.orientation
                && subfileType == that.subfileType && Double.compare(xResolution, that.xResolution) == 0
                && Double.compare(yResolution, that.yResolution) == 0 && resolutionUnit == that.resolutionUnit
                && java.util.Arrays.equals(iccProfile, that.iccProfile) && Objects.equals(description, that.description)
                && Objects.equals(pageName, that.pageName) && Objects.equals(software, that.software)
                && Objects.equals(dateTime, that.dateTime) && Objects.equals(make, that.make)
                && Objects.equals(model, that.model);
    }

    @Override
    public int hashCode() {
        return Objects.hash(index, width, height, bitsPerSample, samplesPerPixel, sampleFormat, photometric, compression);
    }

    @Override
    public String toString() {
        return "TiffPage[index=" + index + ", width=" + width + ", height=" + height + ", bitsPerSample=" + bitsPerSample
                + ", samplesPerPixel=" + samplesPerPixel + ", sampleFormat=" + sampleFormat + ", photometric=" + photometric
                + ", compression=" + compression + ", predictor=" + predictor + ", planar=" + planar
                + (tileWidth > 0 ? ", tiles=" + tileWidth + "x" + tileHeight : ", rowsPerStrip=" + rowsPerStrip)
                + ", alpha=" + hasAlpha() + ", orientation=" + orientation + "]";
    }
}
