package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.RawFormat;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.io.MediaSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds what a parser learned about a raw file: the camera and the shot, the embedded previews,
 * the EXIF metadata, the sensor image and its color description.
 *
 * <p>Fields are set by the parsers and read by the pipeline and the public API; an instance is
 * confined to one call.
 */
public final class RawFile {
    /**
     * An embedded JPEG preview.
     *
     * <p>A preview whose picture is letterboxed shows the image only in {@code rows} rows from row
     * {@code top}; the rows above and below are bars.
     *
     * @param offset the absolute offset of the JPEG stream
     * @param length the length of the stream
     * @param width the width, or {@code 0} when unknown
     * @param height the height, or {@code 0} when unknown
     * @param damagedStart whether the stream's first byte is stored as another value and reads as
     * {@code 0xFF}, the first byte of the start-of-image marker
     * @param top the first row showing the image
     * @param rows the rows showing the image, {@code height} when the preview is not letterboxed
     */
    record Preview(long offset, long length, int width, int height, boolean damagedStart, int top, int rows) {
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
            return new Preview(offset, length, width, height, damagedStart, (height - shown) / 2, shown);
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
     * The embedded JPEG previews.
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
     * The color description.
     */
    final ColorCalibration color = new ColorCalibration();

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
