package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.image.jxl.JxlDecoder;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.ResizeFilter;
import com.github.auties00.smalt.image.raw.CfaColor;
import com.github.auties00.smalt.image.raw.CfaLayout;
import com.github.auties00.smalt.image.raw.CfaPattern;
import com.github.auties00.smalt.image.raw.RawDecoderOptions;
import com.github.auties00.smalt.image.raw.RawFormat;
import com.github.auties00.smalt.image.raw.RawPreview;
import com.github.auties00.smalt.image.raw.RawWhiteBalance;
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
     * The color planes of a three-color image.
     */
    private static final List<CfaColor> RGB_PLANES = List.of(CfaColor.RED, CfaColor.GREEN, CfaColor.BLUE);

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
     * @param layout the spatial layout of the color filter array of the rendered area
     * @param profiles the names of the camera profiles, the primary one first
     * @param enhanced whether the file holds an enhanced image that decodes
     */
    public record Facts(RawFormat format, String make, String model, int width, int height, int sensorWidth,
                        int sensorHeight, Orientation orientation, int bitsPerSample, CfaPattern cfa,
                        String compression, String unsupported, boolean calibrated, long iso, double exposureTime,
                        double fNumber, double focalLength, String lens, String captureTime, String dngVersion,
                        List<RawPreview> previews, CfaLayout layout, List<String> profiles, boolean enhanced) {
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
     * @param balancedToXyz the white-balanced camera to XYZ matrix, or an empty array for a four-color
     * camera of unknown colors
     * @param layout the spatial layout of the color filter array
     * @param planes the color of each component of the neutral
     * @param window the left, top, width and height of the image in the sensor's active area
     */
    public record SensorImage(int width, int height, int channels, short[] samples, CfaPattern cfa, double[] neutral,
                              double[] balancedToXyz, CfaLayout layout, List<CfaColor> planes, int[] window) {
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
        var layout = CfaLayout.RECTANGULAR;
        if (sensor != null && file.unsupported == null) {
            var area = sensor.active();
            var window = Renderer.window(sensor, area[3] - area[1], area[2] - area[0], true, true);
            var size = Renderer.scaledSize(sensor, window[2], window[3]);
            width = size[0];
            height = size[1];
            if (sensor.cfa != null) {
                cfa = sensor.cfa.shifted(area[0] + window[1], area[1] + window[0]);
                layout = sensor.layout.shifted(window[1], window[0]);
            }
        }
        var previews = new ArrayList<RawPreview>();
        for (var preview : sortedPreviews(file)) {
            if (preview.width() > 0 && preview.height() > 0 && preview.length() > 0) {
                previews.add(new RawPreview(preview.width(), preview.height(), preview.length(),
                        preview.pixels() != null ? ImageFormat.RAW : preview.image() != null ? ImageFormat.JXL
                                : ImageFormat.JPEG));
            }
        }
        var calibrated = file.color.colorMatrix[0] != null && !file.color.generic || file.color.cameraToSrgb != null;
        var unsupported = sensor == null && file.unsupported == null ? "the file holds no sensor image" : file.unsupported;
        if (unsupported == null) {
            unsupported = Renderer.fourColorProblem(file);
        }
        return new Facts(file.format, file.make, file.model, width, height, sensor != null ? sensor.width : 0,
                sensor != null ? sensor.height : 0, Orientation.ofExif(file.orientation),
                sensor != null ? sensor.bitsPerSample : 0, cfa,
                sensor != null ? sensor.compression : null, unsupported, calibrated, file.iso, file.exposureTime,
                file.fNumber, file.focalLength, file.lens, file.captureTime, file.dngVersion, previews, layout,
                file.profileNames(), file.enhanced != null);
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
        for (var preview : sortedPreviews(file)) {
            if (preview.image() == null && preview.pixels() == null) {
                return previewBytes(file, preview);
            }
        }
        return null;
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
     * Decodes an embedded preview: a JPEG stream, its first byte repaired when it is stored damaged
     * and its letterbox bars cut off, a JPEG XL image or RGB pixels.
     *
     * @param file the parsed file
     * @param preview one of its previews
     * @param options the decoding settings: limits and size hint, which the image area meets
     * @return the picture of the preview's image area, at the size the hint allows
     * @throws MediaException.Malformed if the preview is not a decodable JPEG stream
     */
    static Picture previewPicture(RawFile file, RawFile.Preview preview, ImageDecoderOptions options) {
        if (preview.image() != null) {
            return imagePreview(file, preview.image(), options);
        }
        if (preview.pixels() != null) {
            return pixelPreview(file, preview, options);
        }
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
     * Decodes a preview stored as TIFF image data (a DNG 1.7 JPEG XL preview, one tile or several)
     * into an RGBA picture: 8-bit samples to an 8-bit picture, deeper ones to a 16-bit picture when
     * the options keep the depth, a gray preview to equal channels.
     *
     * @param file the parsed file
     * @param ifd the preview's directory
     * @param options the decoding settings: limits, size hint and bit depth
     * @return the picture, reduced by halving while the size hint allows
     */
    private static Picture imagePreview(RawFile file, Ifd ifd, ImageDecoderOptions options) {
        var width = ifd.intValue(TiffSamples.IMAGE_WIDTH, 0);
        var height = ifd.intValue(TiffSamples.IMAGE_LENGTH, 0);
        if (width <= 0 || height <= 0) {
            throw new MediaException.Malformed("preview of " + width + "x" + height);
        }
        LimitChecks.checkPicture(file.limits, width, height);
        var high = options.highBitDepth() && ifd.intValue(TiffSamples.BITS_PER_SAMPLE, 8) > 8;
        LimitChecks.checkAllocation(file.limits, (long) width * height * (high ? 8 : 4));
        var tileOptions = ImageDecoderOptions.builder().limits(file.limits).highBitDepth(high).build();
        Picture picture;
        if (high) {
            var out = Rgba16Picture.allocate(width, height, false);
            for (var block : TiffSamples.blocks(ifd, width, height)) {
                var tile = ImageOps.toRgba16(JxlDecoder.decode(MediaSource.of(blockBytes(file, block)),
                        tileOptions).picture());
                place(tile.pixels(), tile.stride(), tile.width(), tile.height(), out.pixels(), out.stride(), width,
                        height, block);
            }
            picture = out;
        } else {
            var out = RgbaPicture.allocate(width, height, false);
            for (var block : TiffSamples.blocks(ifd, width, height)) {
                var tile = ImageOps.toRgba(JxlDecoder.decode(MediaSource.of(blockBytes(file, block)), tileOptions).picture());
                place(tile.pixels(), tile.stride(), tile.width(), tile.height(), out.pixels(), out.stride(), width,
                        height, block);
            }
            picture = out;
        }
        return reduced(picture, options);
    }

    /**
     * Decodes a preview stored as 8-bit RGB pixels into an opaque RGBA picture.
     *
     * @param file the parsed file
     * @param preview a preview of the file stored as pixels
     * @param options the decoding settings: limits and size hint
     * @return the picture, reduced by halving while the size hint allows
     * @throws MediaException.Malformed if the coded pixels are corrupt
     */
    private static Picture pixelPreview(RawFile file, RawFile.Preview preview, ImageDecoderOptions options) {
        var width = preview.width();
        var height = preview.height();
        LimitChecks.checkPicture(file.limits, width, height);
        LimitChecks.checkAllocation(file.limits, (long) width * height * 7);
        var rgb = preview.pixels().read(file.limits);
        var picture = RgbaPicture.allocate(width, height, false);
        var pixels = picture.pixels();
        for (int source = 0, target = 0; target < pixels.length; source += 3, target += 4) {
            pixels[target] = rgb[source];
            pixels[target + 1] = rgb[source + 1];
            pixels[target + 2] = rgb[source + 2];
            pixels[target + 3] = (byte) 0xFF;
        }
        return reduced(picture, options);
    }

    /**
     * Halves a picture's size while the size hint allows.
     *
     * @param picture the decoded preview
     * @param options the size hint
     * @return the picture, or a reduced copy
     */
    private static Picture reduced(Picture picture, ImageDecoderOptions options) {
        var width = picture.width();
        var height = picture.height();
        var targetWidth = width;
        var targetHeight = height;
        while ((options.minWidth() > 0 || options.minHeight() > 0) && targetWidth / 2 >= Math.max(1, options.minWidth())
                && targetHeight / 2 >= Math.max(1, options.minHeight())) {
            targetWidth /= 2;
            targetHeight /= 2;
        }
        if (targetWidth != width || targetHeight != height) {
            picture = ImageOps.resize(picture, targetWidth, targetHeight, ResizeFilter.AREA);
        }
        return picture;
    }

    /**
     * Reads the bytes of a strip or tile.
     *
     * @param file the parsed file
     * @param block the strip or tile
     * @return its bytes
     */
    private static byte[] blockBytes(RawFile file, TiffSamples.Block block) {
        LimitChecks.checkAllocation(file.limits, block.length());
        if (block.length() > Integer.MAX_VALUE - 16) {
            throw new MediaException.LimitExceeded("preview tile of " + block.length() + " bytes");
        }
        var bytes = new byte[(int) block.length()];
        file.source.readFully(block.offset(), bytes, 0, bytes.length);
        return bytes;
    }

    /**
     * Copies a decoded tile into its place in a picture of the same sample type, clipped to both.
     *
     * @param <T> the pixel array type, {@code byte[]} or {@code short[]}
     * @param tile the tile's pixels
     * @param tileStride the tile's row stride in samples
     * @param tileWidth the tile's width
     * @param tileHeight the tile's height
     * @param out the picture's pixels
     * @param outStride the picture's row stride in samples
     * @param width the picture's width
     * @param height the picture's height
     * @param block the tile's place
     */
    private static <T> void place(T tile, int tileStride, int tileWidth, int tileHeight, T out, int outStride, int width,
                                  int height, TiffSamples.Block block) {
        var rows = Math.min(Math.min(tileHeight, block.height()), height - block.y());
        var columns = Math.min(Math.min(tileWidth, block.width()), width - block.x());
        for (var row = 0; row < rows; row++) {
            System.arraycopy(tile, row * tileStride, out, (block.y() + row) * outStride + block.x() * 4, columns * 4);
        }
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
            var unsupported = facts.unsupported();
            if (unsupported != null && options.colorMatrix().isPresent()
                    && unsupported.equals(Renderer.NO_FOUR_COLOR_MATRICES)) {
                unsupported = null;
            }
            if (unsupported != null || facts.width() <= 0) {
                throw new MediaException.Unsupported(file.format.extension(), unsupported != null ? unsupported
                        : "the file holds no sensor image");
            }
            var sensor = file.sensor(options);
            var width = facts.width();
            var height = facts.height();
            if (sensor != file.sensor) {
                var area = sensor.active();
                var window = Renderer.window(sensor, area[3] - area[1], area[2] - area[0], true, true);
                var size = Renderer.scaledSize(sensor, window[2], window[3]);
                width = size[0];
                height = size[1];
            }
            if (hinted && sensor == file.sensor && options.defaultCrop() && options.opcodes()) {
                var proxy = proxy(file, options, width, height);
                if (proxy != null) {
                    return new Rendering(Renderer.render(file, proxy, options, false), false, width, height);
                }
            }
            var half = hinted && width / 2 >= options.minWidth() && height / 2 >= options.minHeight();
            var picture = Renderer.render(file, sensor, options, half);
            return new Rendering(picture, false, width, height);
        });
    }

    /**
     * Returns the reduced-resolution linear raw preview that best meets a size hint: the smallest
     * that meets it and is smaller than the half-size rendering, or than the full rendering when a
     * half-size rendering (of a Bayer image only) does not meet the hint. Such a preview shows the
     * default crop with the opcode lists applied, so the caller asks only when the options apply both.
     *
     * @param file the parsed file
     * @param options the rendering settings, with a size hint
     * @param width the width of the full rendering
     * @param height the height of the full rendering
     * @return the preview, or {@code null} when none is better
     */
    private static Sensor proxy(RawFile file, RawDecoderOptions options, int width, int height) {
        var sensor = file.sensor;
        var halves = sensor.cfa != null && sensor.cfa.isBayer() && !sensor.layout.isStaggered();
        var half = halves && width / 2 >= options.minWidth() && height / 2 >= options.minHeight();
        var limit = half ? (long) (width / 2) * (height / 2) : (long) width * height;
        Sensor chosen = null;
        for (var proxy : file.proxies) {
            var area = proxy.active();
            var window = Renderer.window(proxy, area[3] - area[1], area[2] - area[0], true, true, options.userCrop());
            var pixels = (long) window[2] * window[3];
            var meets = window[2] >= options.minWidth() && window[3] >= options.minHeight();
            if (meets && pixels < limit) {
                limit = pixels;
                chosen = proxy;
            }
        }
        return chosen;
    }

    /**
     * Extracts the linear sensor image of a file.
     *
     * @param file the parsed file
     * @param options the options: limits, opcodes, default crop, white balance and color matrix
     * @return the sensor image
     */
    public static SensorImage sensorImage(RawFile file, RawDecoderOptions options) {
        return sensorImage(file, options, false);
    }

    /**
     * Extracts the sensor image, before demosaicing and color rendering.
     *
     * @param file the parsed file
     * @param options the decoding options
     * @param whole whether the image covers the whole active area, whatever the crop choices and the
     * bounds of the opcode lists
     * @return the sensor image
     */
    public static SensorImage sensorImage(RawFile file, RawDecoderOptions options, boolean whole) {
        return Guards.malformed(WHAT, () -> {
            var sensor = file.sensor(options);
            var problem = Renderer.fourColorPlanesProblem(sensor);
            if (problem != null) {
                throw new MediaException.Unsupported(file.format.extension(), problem);
            }
            var image = Renderer.linearize(file, sensor, options.opcodes());
            var window = whole ? new int[]{0, 0, image.width, image.height} : Renderer.window(sensor, image.width,
                    image.height, options.defaultCrop(), options.opcodes(), options.userCrop());
            if (sensor == file.sensor) {
                PreviewBalance.apply(file, image, Renderer.window(sensor, image, true));
            }
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
            var layout = image.cfa != null ? sensor.layout.shifted(window[1], window[0]) : CfaLayout.RECTANGULAR;
            var planes = planes(sensor, cfa);
            var calibration = file.color(options);
            var colors = image.cfa != null && !image.cfa.isRgb() ? 4 : 3;
            var matrix = options.colorMatrix().orElse(null);
            if (colors == 4 && matrix == null && calibration.channels() != 4) {
                return new SensorImage(window[2], window[3], spp, samples, cfa, uncalibratedNeutral(file, calibration,
                        options.whiteBalance()), new double[0], layout, planes, window);
            }
            var color = CameraColor.of(calibration, options.whiteBalance(), matrix, colors);
            return new SensorImage(window[2], window[3], spp, samples, cfa, color.neutral, color.balancedToPcs, layout,
                    planes, window);
        });
    }

    /**
     * Returns the color of each color plane of a sensor image: red, green and blue, or the four colors of a
     * four-color filter array in the order of its color planes.
     *
     * @param sensor the sensor description
     * @param cfa the color filter array of the image, or {@code null}
     * @return the colors
     */
    private static List<CfaColor> planes(Sensor sensor, CfaPattern cfa) {
        if (cfa == null || cfa.isRgb()) {
            return RGB_PLANES;
        }
        var planes = new CfaColor[4];
        for (var color : cfa.colors()) {
            var plane = sensor.planeOfColor[color.code()];
            if (plane >= 0 && plane < 4) {
                planes[plane] = color;
            }
        }
        for (var plane : planes) {
            if (plane == null) {
                return RGB_PLANES;
            }
        }
        return List.of(planes);
    }

    /**
     * Returns the camera neutral of a four-color camera whose colors are unknown: the reciprocals
     * of four multipliers, or the as-shot neutral when it has four components, or all ones.
     *
     * @param file the parsed file
     * @param calibration the color description
     * @param balance the white balance
     * @return the neutral, largest component 1
     * @throws MediaException.Unsupported if the white balance is a temperature or has other than four
     * multipliers, which a camera of unknown colors cannot render
     */
    private static double[] uncalibratedNeutral(RawFile file, ColorCalibration calibration, RawWhiteBalance balance) {
        return switch (balance) {
            case RawWhiteBalance.Multipliers multipliers -> {
                if (multipliers.factors().size() != 4) {
                    throw new MediaException.Unsupported(file.format.extension(), multipliers.factors().size()
                            + " multipliers cannot white balance a 4-color camera");
                }
                var neutral = new double[4];
                for (var index = 0; index < 4; index++) {
                    neutral[index] = 1 / multipliers.factors().get(index);
                }
                yield CameraColor.normalize(neutral, 4);
            }
            case RawWhiteBalance.Temperature _ -> throw new MediaException.Unsupported(file.format.extension(),
                    "a color temperature cannot white balance a four-color camera without color matrices");
            case RawWhiteBalance.AsShot _ -> {
                var shot = calibration.asShotNeutral;
                var valid = shot != null && shot.length >= 4;
                for (var index = 0; valid && index < 4; index++) {
                    valid = shot[index] > 0 && Double.isFinite(shot[index]);
                }
                yield valid ? CameraColor.normalize(shot, 4) : ColorMath.filled(4, 1);
            }
        };
    }
}
