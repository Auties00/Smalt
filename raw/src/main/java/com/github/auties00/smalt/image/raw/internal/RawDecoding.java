package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.raw.CfaPattern;
import com.github.auties00.smalt.image.raw.RawDecoderOptions;
import com.github.auties00.smalt.image.raw.RawFormat;
import com.github.auties00.smalt.image.raw.RawPreview;
import com.github.auties00.smalt.internal.io.SlicedSource;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Carries out the operations of the public raw decoder over the internal model: describing a
 * file, choosing between the embedded preview, a half-size and a full rendering, and extracting
 * the sensor image.
 *
 * <p>Parsing, rendering and extraction run under the decoder policy of {@link Guards}: a failure
 * other than a {@link MediaException} is reported as a corrupt raw file, and running out of memory
 * or stack as {@link MediaException.LimitExceeded}.
 */
public final class RawDecoding {
    /**
     * The input, named in failure messages.
     */
    private static final String WHAT = "raw file";

    /**
     * Prevents instantiation.
     */
    private RawDecoding() {
        throw new AssertionError();
    }

    /**
     * Summarizes a raw file.
     *
     * @param format the raw format of the file
     * @param make the camera maker, or {@code null}
     * @param model the camera model, or {@code null}
     * @param width the rendered width, or {@code 0}
     * @param height the rendered height, or {@code 0}
     * @param sensorWidth the stored sensor width, or {@code 0}
     * @param sensorHeight the stored sensor height, or {@code 0}
     * @param orientation the rotation that displays the stored image upright
     * @param bitsPerSample the stored bits per sample, or {@code 0}
     * @param cfa the color filter array of the rendered area, or {@code null}
     * @param compression the compression, or {@code null}
     * @param unsupported the feature keeping the sensor data from decoding, or {@code null}
     * @param calibrated whether the decoder knows the camera's colors
     * @param iso the ISO speed, or {@code 0}
     * @param exposureTime the exposure time, or {@code 0}
     * @param fNumber the f-number, or {@code 0}
     * @param focalLength the focal length, or {@code 0}
     * @param lens the lens, or {@code null}
     * @param captureTime the capture time, or {@code null}
     * @param dngVersion the DNG version, or {@code null}
     * @param previews the previews of a known size, largest first
     */
    public record Facts(RawFormat format, String make, String model, int width, int height, int sensorWidth,
                        int sensorHeight, Orientation orientation, int bitsPerSample, CfaPattern cfa,
                        String compression, String unsupported, boolean calibrated, long iso, double exposureTime,
                        double fNumber, double focalLength, String lens, String captureTime, String dngVersion,
                        List<RawPreview> previews) {
        /**
         * {@return whether the sensor image decodes: no feature is missing and the rendering has a
         * size}
         */
        public boolean isDecodable() {
            return unsupported == null && width > 0;
        }
    }

    /**
     * Holds a rendering.
     *
     * @param picture the pixels
     * @param preview whether they come from the embedded preview
     * @param width the width of the full rendering
     * @param height the height of the full rendering
     */
    public record Rendering(Picture picture, boolean preview, int width, int height) {
    }

    /**
     * Holds a sensor image.
     *
     * @param width the width in pixels
     * @param height the height in pixels
     * @param channels the samples of each pixel
     * @param samples the 16-bit samples
     * @param cfa the color filter array, or {@code null}
     * @param neutral the camera neutral
     * @param balancedToXyz the white-balanced camera to XYZ matrix
     */
    public record SensorImage(int width, int height, int channels, short[] samples, CfaPattern cfa, double[] neutral,
                              double[] balancedToXyz) {
    }

    /**
     * Parses a raw file.
     *
     * @param source the raw file, read but not closed
     * @param limits the resource bounds
     * @return the parsed file
     */
    public static RawFile open(MediaSource source, MediaLimits limits) {
        return Guards.malformed(WHAT, () -> RawParser.parse(source, limits));
    }

    /**
     * Summarizes a parsed file.
     *
     * @param file the parsed file
     * @return the facts
     */
    public static Facts facts(RawFile file) {
        var sensor = file.sensor;
        var width = 0;
        var height = 0;
        CfaPattern cfa = null;
        if (sensor != null && file.unsupported == null) {
            var area = sensor.active();
            var window = Renderer.window(sensor, area[3] - area[1], area[2] - area[0], true, true);
            var size = Renderer.scaledSize(sensor, window[2], window[3]);
            width = size[0];
            height = size[1];
            if (sensor.cfa != null) {
                cfa = sensor.cfa.shifted(area[0] + window[1], area[1] + window[0]);
            }
        }
        var previews = new ArrayList<RawPreview>();
        for (var preview : sortedPreviews(file)) {
            if (preview.width() > 0 && preview.height() > 0 && preview.length() > 0) {
                previews.add(new RawPreview(preview.width(), preview.height(), preview.length()));
            }
        }
        var calibrated = file.color.colorMatrix[0] != null || file.color.cameraToSrgb != null;
        var unsupported = sensor == null && file.unsupported == null ? "the file holds no sensor image" : file.unsupported;
        if (unsupported == null) {
            unsupported = Renderer.fourColorProblem(file);
        }
        return new Facts(file.format, file.make, file.model, width, height, sensor != null ? sensor.width : 0,
                sensor != null ? sensor.height : 0, Orientation.ofExif(file.orientation),
                sensor != null ? sensor.bitsPerSample : 0, cfa,
                sensor != null ? sensor.compression : null, unsupported, calibrated, file.iso, file.exposureTime,
                file.fNumber, file.focalLength, file.lens, file.captureTime, file.dngVersion, previews);
    }

    /**
     * Returns the previews with a known size, largest first.
     *
     * @param file the parsed file
     * @return the previews
     */
    private static List<RawFile.Preview> sortedPreviews(RawFile file) {
        var previews = new ArrayList<>(file.previews);
        previews.sort(Comparator.comparingLong((RawFile.Preview preview) -> (long) preview.width() * preview.height())
                .reversed());
        return previews;
    }

    /**
     * Returns the EXIF block of a file.
     *
     * @param file the parsed file
     * @return the block, or {@code null}
     */
    public static byte[] exif(RawFile file) {
        return file.exif;
    }

    /**
     * Reads the bytes of the largest embedded JPEG preview.
     *
     * @param file the parsed file
     * @return the JPEG stream, or {@code null} when the file embeds none
     */
    public static byte[] largestPreview(RawFile file) {
        var previews = sortedPreviews(file);
        if (previews.isEmpty()) {
            return null;
        }
        return previewBytes(file, previews.getFirst());
    }

    /**
     * Reads the bytes of an embedded JPEG preview, its first byte repaired when it is stored damaged.
     *
     * @param file the parsed file
     * @param preview one of its previews
     * @return the JPEG stream
     */
    private static byte[] previewBytes(RawFile file, RawFile.Preview preview) {
        LimitChecks.checkAllocation(file.limits, preview.length());
        if (preview.length() > Integer.MAX_VALUE - 16) {
            throw new MediaException.LimitExceeded("preview of " + preview.length() + " bytes");
        }
        var bytes = new byte[(int) preview.length()];
        file.source.readFully(preview.offset(), bytes, 0, bytes.length);
        if (preview.damagedStart() && bytes.length > 0) {
            bytes[0] = (byte) 0xFF;
        }
        return bytes;
    }

    /**
     * Decodes an embedded JPEG preview, its first byte repaired when it is stored damaged and its
     * letterbox bars cut off.
     *
     * @param file the parsed file
     * @param preview one of its previews
     * @param options the decoding settings: limits and size hint, which the image area meets
     * @return the picture of the preview's image area, at the size the hint allows
     * @throws MediaException.Malformed if the preview is not a decodable JPEG stream
     */
    static Picture previewPicture(RawFile file, RawFile.Preview preview, ImageDecoderOptions options) {
        MediaSource source = preview.damagedStart() ? MediaSource.of(previewBytes(file, preview))
                : new SlicedSource(file.source, preview.offset(), preview.length());
        var letterboxed = preview.height() > 0 && preview.rows() > 0 && preview.rows() < preview.height();
        if (letterboxed && options.minHeight() > 0) {
            var minHeight = (int) Math.min(Integer.MAX_VALUE,
                    ((long) options.minHeight() * preview.height() + preview.rows() - 1) / preview.rows());
            options = options.toBuilder().minSize(options.minWidth(), minHeight).build();
        }
        var picture = JpegDecoder.decode(source, options).picture();
        if (!letterboxed) {
            return picture;
        }
        var top = (int) ((long) preview.top() * picture.height() / preview.height());
        var rows = Math.max(1, (int) ((long) preview.rows() * picture.height() / preview.height()));
        return ImageOps.crop(picture, 0, top, picture.width(), Math.min(rows, picture.height() - top));
    }

    /**
     * Renders a file: from the embedded preview or at half size when the size hint allows, at full
     * size otherwise.
     *
     * @param file the parsed file
     * @param options the rendering settings, size hint and limits
     * @return the rendering
     */
    public static Rendering render(RawFile file, RawDecoderOptions options) {
        return Guards.malformed(WHAT, () -> {
            var facts = facts(file);
            var hinted = options.minWidth() > 0 || options.minHeight() > 0;
            if (hinted && options.embeddedPreview()) {
                RawFile.Preview chosen = null;
                for (var preview : sortedPreviews(file)) {
                    if (preview.width() >= options.minWidth() && preview.rows() >= options.minHeight()) {
                        chosen = preview;
                    }
                }
                if (chosen != null || !facts.isDecodable()) {
                    var preview = chosen != null ? chosen : sortedPreviews(file).stream().findFirst().orElse(null);
                    if (preview != null) {
                        var picture = previewPicture(file, preview, ImageDecoderOptions.builder().limits(options.limits())
                                .minSize(options.minWidth(), options.minHeight()).build());
                        var width = facts.width() > 0 ? facts.width() : preview.width();
                        var height = facts.height() > 0 ? facts.height() : preview.rows();
                        return new Rendering(picture, true, width, height);
                    }
                }
            }
            if (!facts.isDecodable()) {
                throw new MediaException.Unsupported(file.format.extension(), facts.unsupported());
            }
            var half = hinted && facts.width() / 2 >= options.minWidth() && facts.height() / 2 >= options.minHeight();
            var picture = Renderer.render(file, options, half);
            return new Rendering(picture, false, facts.width(), facts.height());
        });
    }

    /**
     * Extracts the linear sensor image of a file.
     *
     * @param file the parsed file
     * @param options the options: limits, opcodes, default crop, white balance and color matrix
     * @return the sensor image
     */
    public static SensorImage sensorImage(RawFile file, RawDecoderOptions options) {
        return Guards.malformed(WHAT, () -> {
            var problem = Renderer.fourColorProblem(file);
            if (problem != null) {
                throw new MediaException.Unsupported(file.format.extension(), problem);
            }
            var image = Renderer.linearize(file, options.opcodes());
            var window = Renderer.window(file.sensor, image.width, image.height, options.defaultCrop(), options.opcodes());
            PreviewBalance.apply(file, image, Renderer.window(file.sensor, image, true));
            var spp = image.samplesPerPixel;
            var count = (long) window[2] * window[3] * spp;
            LimitChecks.checkAllocation(file.limits, count * 2);
            var samples = new short[(int) count];
            var rows = window[3];
            var bands = ParallelBands.bands(rows, count);
            ParallelBands.run(bands, band -> {
                var last = ParallelBands.start(band + 1, bands, rows);
                for (var row = ParallelBands.start(band, bands, rows); row < last; row++) {
                    var source = ((window[1] + row) * image.width + window[0]) * spp;
                    var target = row * window[2] * spp;
                    for (var index = 0; index < window[2] * spp; index++) {
                        var value = image.data[source + index];
                        samples[target + index] = (short) Math.round(Math.clamp(value, 0f, 1f) * 65535f);
                    }
                }
            });
            var cfa = image.cfa != null ? image.cfa.shifted(window[1], window[0]) : null;
            var color = CameraColor.of(file.color, options.whiteBalance(), options.colorMatrix().orElse(null));
            return new SensorImage(window[2], window[3], spp, samples, cfa, color.neutral, color.balancedToPcs);
        });
    }
}
