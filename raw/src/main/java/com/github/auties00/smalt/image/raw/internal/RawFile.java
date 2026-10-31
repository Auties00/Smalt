package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.RawDecoderOptions;
import com.github.auties00.smalt.image.raw.RawFormat;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.io.MediaSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Holds what a parser learned about a raw file: the camera and the shot, the embedded previews,
 * the EXIF metadata, the sensor image and its color description.
 *
 * <p>Fields are set by the parsers and read by the pipeline and the public API; an instance is
 * confined to one call.
 */
public final class RawFile {
    /**
     * Decodes a preview stored as 8-bit RGB pixels in the raw file's own coding.
     */
    @FunctionalInterface
    interface Pixels {
        /**
         * Decodes the pixels.
         *
         * @param limits the resource bounds
         * @return the red, green and blue values of each pixel, row by row
         * @throws MediaException.Malformed if the coded pixels are corrupt
         */
        byte[] read(MediaLimits limits);
    }

    /**
     * An embedded preview: a JPEG stream, a JPEG XL image of a DNG file, or 8-bit RGB pixels coded
     * in the raw file's own format.
     *
     * <p>A preview whose picture is letterboxed shows the image only in {@code rows} rows from row
     * {@code top}; the rows above and below are bars.
     *
     * @param offset the absolute offset of the JPEG stream, or of the coded pixels
     * @param length the length of the stream
     * @param width the width, or {@code 0} when unknown
     * @param height the height, or {@code 0} when unknown
     * @param damagedStart whether the stream's first byte is stored as another value and reads as
     * {@code 0xFF}, the first byte of the start-of-image marker
     * @param top the first row showing the image
     * @param rows the rows showing the image, {@code height} when the preview is not letterboxed
     * @param image the directory of a preview stored as TIFF image data (a DNG 1.7 JPEG XL preview)
     * rather than as one JPEG stream, or {@code null}
     * @param pixels the decoder of a preview stored as RGB pixels rather than as a JPEG stream, or
     * {@code null}
     */
    record Preview(long offset, long length, int width, int height, boolean damagedStart, int top, int rows,
                   Ifd image, Pixels pixels) {
        /**
         * Constructs the description of a JPEG stream or JPEG XL image preview.
         *
         * @param offset the absolute offset of the JPEG stream
         * @param length the length of the stream
         * @param width the width, or {@code 0} when unknown
         * @param height the height, or {@code 0} when unknown
         * @param damagedStart whether the stream's first byte is to be read as {@code 0xFF}
         * @param top the first row showing the image
         * @param rows the rows showing the image
         * @param image the directory of a JPEG XL preview, or {@code null}
         */
        Preview(long offset, long length, int width, int height, boolean damagedStart, int top, int rows, Ifd image) {
            this(offset, length, width, height, damagedStart, top, rows, image, null);
        }

        /**
         * Constructs the description of a preview stored as RGB pixels.
         *
         * @param offset the absolute offset of the coded pixels
         * @param length the length of the coded pixels
         * @param width the width in pixels
         * @param height the height in pixels
         * @param pixels the decoder of the pixels
         */
        Preview(long offset, long length, int width, int height, Pixels pixels) {
            this(offset, length, width, height, false, 0, height, null, pixels);
        }

        /**
         * Constructs the description of a JPEG stream preview.
         *
         * @param offset the absolute offset of the JPEG stream
         * @param length the length of the stream
         * @param width the width, or {@code 0} when unknown
         * @param height the height, or {@code 0} when unknown
         * @param damagedStart whether the stream's first byte is to be read as {@code 0xFF}
         * @param top the first row showing the image
         * @param rows the rows showing the image
         */
        Preview(long offset, long length, int width, int height, boolean damagedStart, int top, int rows) {
            this(offset, length, width, height, damagedStart, top, rows, null);
        }

        /**
         * Constructs the description of a preview stored intact and not letterboxed.
         *
         * @param offset the absolute offset of the JPEG stream
         * @param length the length of the stream
         * @param width the width, or {@code 0} when unknown
         * @param height the height, or {@code 0} when unknown
         */
        Preview(long offset, long length, int width, int height) {
            this(offset, length, width, height, false, 0, height);
        }

        /**
         * Constructs the description of a preview that is not letterboxed.
         *
         * @param offset the absolute offset of the JPEG stream
         * @param length the length of the stream
         * @param width the width, or {@code 0} when unknown
         * @param height the height, or {@code 0} when unknown
         * @param damagedStart whether the stream's first byte is to be read as {@code 0xFF}
         */
        Preview(long offset, long length, int width, int height, boolean damagedStart) {
            this(offset, length, width, height, damagedStart, 0, height);
        }

        /**
         * Returns this preview letterboxed to show an image of another aspect ratio across its
         * whole width, centered between bars of equal height (the lower one a row taller when the
         * difference is odd).
         *
         * @param imageWidth the width of the image the preview shows
         * @param imageHeight the height of the image the preview shows
         * @return the letterboxed preview, or this preview when the image is not wider than the
         * preview by at least two rows
         */
        Preview letterboxed(int imageWidth, int imageHeight) {
            if (width <= 0 || height <= 0 || imageWidth <= 0 || imageHeight <= 0) {
                return this;
            }
            var shown = (int) ((long) width * imageHeight / imageWidth);
            if (shown > height - 2) {
                return this;
            }
            return new Preview(offset, length, width, height, damagedStart, (height - shown) / 2, shown, image, pixels);
        }
    }

    /**
     * The source the file is read from.
     */
    final MediaSource source;

    /**
     * The resource bounds.
     */
    final MediaLimits limits;

    /**
     * The format.
     */
    RawFormat format;

    /**
     * The camera maker, or {@code null}.
     */
    String make;

    /**
     * The camera model, or {@code null}.
     */
    String model;

    /**
     * The DNG {@code UniqueCameraModel}, or {@code null}.
     */
    String uniqueCameraModel;

    /**
     * The DNG version, such as {@code 1.4.0.0}, or {@code null}.
     */
    String dngVersion;

    /**
     * The EXIF orientation code.
     */
    int orientation = 1;

    /**
     * The EXIF metadata as a stand-alone TIFF structure, or {@code null}.
     */
    byte[] exif;

    /**
     * The ISO speed, or {@code 0}.
     */
    long iso;

    /**
     * The exposure time in seconds, or {@code 0}.
     */
    double exposureTime;

    /**
     * The f-number, or {@code 0}.
     */
    double fNumber;

    /**
     * The focal length in millimeters, or {@code 0}.
     */
    double focalLength;

    /**
     * The lens model, or {@code null}.
     */
    String lens;

    /**
     * The capture time as EXIF writes it, or {@code null}.
     */
    String captureTime;

    /**
     * The embedded previews.
     */
    final List<Preview> previews = new ArrayList<>();

    /**
     * The sensor image, or {@code null} when the file holds none this decoder reads.
     */
    Sensor sensor;

    /**
     * The feature that keeps the sensor image from decoding, or {@code null}.
     */
    String unsupported;

    /**
     * The color description, with the primary camera profile.
     */
    final ColorCalibration color = new ColorCalibration();

    /**
     * The profile-level color descriptions of the extra camera profiles of a DNG file
     * ({@code ExtraCameraProfiles}), in file order; combined with {@link #color} when selected.
     */
    final List<ColorCalibration> extraProfiles = new ArrayList<>();

    /**
     * The name of the camera profile the shot was taken with ({@code AsShotProfileName}), or
     * {@code null}.
     */
    String asShotProfile;

    /**
     * The enhanced image of a DNG file (DNG 1.5, {@code NewSubFileType} 16), or {@code null}.
     */
    Sensor enhanced;

    /**
     * The reduced-resolution linear raw images of a DNG file (previews of photometric
     * interpretation {@code LinearRaw}), in file order.
     */
    final List<Sensor> proxies = new ArrayList<>();

    /**
     * The entries of a DNG file's first directory that a DNG file converted from it carries over.
     */
    final List<DngTag> firstTags = new ArrayList<>();

    /**
     * The entries of a DNG file's first directory that describe its primary camera profile and that a DNG file
     * converted from it carries over with that profile.
     */
    final List<DngTag> profileTags = new ArrayList<>();

    /**
     * The semantic masks of a DNG file.
     */
    final List<SemanticMask> masks = new ArrayList<>();

    /**
     * Constructs an empty description.
     *
     * @param source the raw file, read but not closed
     * @param limits the resource bounds
     * @param format the format the parser recognized
     */
    RawFile(MediaSource source, MediaLimits limits, RawFormat format) {
        this.source = source;
        this.limits = limits;
        this.format = format;
    }

    /**
     * Returns the names of the camera profiles, the primary one first, an unnamed profile as an
     * empty string.
     *
     * @return the names
     */
    List<String> profileNames() {
        var names = new ArrayList<String>();
        names.add(color.profileName != null ? color.profileName : "");
        for (var profile : extraProfiles) {
            names.add(profile.profileName != null ? profile.profileName : "");
        }
        return names;
    }

    /**
     * Returns the color description the options render with: the camera profile they name, or by
     * default the as-shot profile when the file names one it holds, else the primary profile.
     *
     * @param options the decoding options
     * @return the description
     * @throws MediaException.Unsupported if the options name a camera profile the file does not hold
     */
    ColorCalibration color(RawDecoderOptions options) {
        var wanted = options.cameraProfile();
        var name = wanted.orElse(asShotProfile);
        if (name == null || name.equals(Objects.requireNonNullElse(color.profileName, ""))) {
            return color;
        }
        for (var profile : extraProfiles) {
            if (name.equals(Objects.requireNonNullElse(profile.profileName, ""))) {
                return color.withProfile(profile);
            }
        }
        if (wanted.isPresent()) {
            throw new MediaException.Unsupported(format.extension(), "the file holds no camera profile named " + name);
        }
        return color;
    }

    /**
     * Returns the image the options render: the frame they ask for of a pixel-shift file, the enhanced
     * image when they ask for it and the file holds one that decodes, the sensor image otherwise.
     *
     * @param options the decoding options
     * @return the sensor description, or {@code null} when the file holds no sensor image
     * @throws MediaException.Unsupported if the options ask for a frame the file does not hold
     */
    Sensor sensor(RawDecoderOptions options) {
        var frame = options.frame();
        if (frame.isPresent() && sensor != null) {
            var index = frame.getAsInt();
            var frames = sensor.frames != null ? sensor.frames.size() : 1;
            if (index >= frames) {
                throw new MediaException.Unsupported(format.extension(), "frame " + index + " of a file of " + frames
                        + (frames == 1 ? " frame" : " frames"));
            }
            return sensor.frames != null ? sensor.frames.get(index) : sensor;
        }
        return options.enhancedImage() && enhanced != null ? enhanced : sensor;
    }

    /**
     * Returns the length of a source, finding the end of one of unknown size by reading.
     *
     * @param source the file, of known or unknown size
     * @return the length
     */
    static long length(MediaSource source) {
        var size = source.size();
        if (size >= 0) {
            return size;
        }
        var probe = new byte[1];
        var low = 0L;
        var high = 1L << 16;
        while (SourceBytes.readAvailable(source, high - 1, probe, 0, 1) == 1) {
            low = high;
            if (high > (1L << 40)) {
                throw new MediaException.LimitExceeded("raw file larger than 1 TiB");
            }
            high <<= 1;
        }
        while (high - low > 1) {
            var middle = (low + high) >>> 1;
            if (SourceBytes.readAvailable(source, middle - 1, probe, 0, 1) == 1) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return low;
    }
}
