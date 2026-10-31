package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifValue;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.ResizeFilter;
import com.github.auties00.smalt.image.raw.DngCompression;
import com.github.auties00.smalt.image.raw.DngEncoderOptions;
import com.github.auties00.smalt.image.raw.RawColorSpace;
import com.github.auties00.smalt.image.raw.RawDecoderOptions;
import com.github.auties00.smalt.image.raw.RawImage;
import com.github.auties00.smalt.image.raw.RawWhiteBalance;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Carries out the operations of the public DNG encoder: writing a sensor image, and converting a raw file into
 * a DNG file that keeps its geometry, color description and metadata.
 *
 * <p>The previews are renderings of the written image: of the source file by a conversion, with the decoding
 * options' rendering choices in 8-bit sRGB, and of the image itself, through the DNG it is written as, by an
 * encoding.
 */
public final class DngWriting {
    /**
     * The input of a conversion, named in failure messages.
     */
    private static final String WHAT = "raw file";

    /**
     * The unique camera model of an image that names no camera.
     */
    private static final String DEFAULT_CAMERA = "Smalt";

    /**
     * The fewest photosites the default crop of a color filter array image leaves on each side, as the DNG SDK's
     * validator asks.
     */
    private static final int MIN_PADDING = 2;

    /**
     * The EXIF {@code ExposureTime} tag.
     */
    private static final int EXPOSURE_TIME = 0x829A;

    /**
     * The EXIF {@code FNumber} tag.
     */
    private static final int F_NUMBER = 0x829D;

    /**
     * The EXIF {@code PhotographicSensitivity} (ISO speed) tag.
     */
    private static final int ISO_SPEED = 0x8827;

    /**
     * The EXIF {@code DateTimeOriginal} tag.
     */
    private static final int DATE_TIME_ORIGINAL = 0x9003;

    /**
     * The EXIF {@code FocalLength} tag.
     */
    private static final int FOCAL_LENGTH = 0x920A;

    /**
     * The EXIF {@code LensModel} tag.
     */
    private static final int LENS_MODEL = 0xA434;

    /**
     * Prevents instantiation.
     */
    private DngWriting() {
        throw new AssertionError();
    }

    /**
     * Writes a sensor image as a DNG file.
     *
     * @param image the sensor image to write
     * @param options the encoder settings
     * @param sink the output, written from its position; not closed
     * @throws IllegalArgumentException if the image is neither monochrome nor has a color matrix, or its matrix
     * is singular or maps a white-balanced neutral to a white with a component that is not positive
     * @throws MediaException.LimitExceeded if the file would exceed 4 GiB
     * @throws MediaException.Io if writing the sink fails
     */
    public static void encode(RawImage image, DngEncoderOptions options, MediaSink sink) {
        var content = new DngContent();
        content.width = image.width();
        content.height = image.height();
        content.channels = image.channels();
        content.samples = image.samples();
        content.cfa = image.cfaPattern().orElse(null);
        content.layout = image.cfaLayout();
        content.planes = image.colorPlanes();
        content.neutral = image.cameraNeutral();
        content.balancedToXyz = image.balancedToXyz();
        if (!monochrome(content) && content.balancedToXyz.length == 0) {
            throw new IllegalArgumentException("an image of " + content.neutral.length
                    + " color planes without a color matrix cannot be written as DNG");
        }
        content.make = options.make().orElse(null);
        content.model = options.model().orElse(null);
        content.uniqueCameraModel = options.uniqueCameraModel().orElse(uniqueModel(content.make, content.model));
        content.orientation = options.orientation().map(Orientation::exifCode).orElse(1);
        content.exif = options.exif().filter(exif -> !exif.equals(ExifData.EMPTY)).orElse(null);
        content.baselineExposure = options.baselineExposure().orElse(0);
        settings(content, options);
        pad(content);
        if (options.preview()) {
            content.preview = selfRendering(content, options);
        }
        DngWriter.write(content, sink);
    }

    /**
     * Converts a raw file into a DNG file.
     *
     * <p>The raw image is the whole active area of the sensor image the decoding options select, with the
     * opcode lists 1 and 2 applied when they apply them; the default crop, the default user crop and opcode list
     * 3 are written for a reader to apply when the options apply the default crop and the opcode lists. The
     * file's color description is kept unless the options replace its color matrix; the options' white balance
     * becomes the file's as-shot neutral, except that the as-shot white balance of a file that records none is
     * left unrecorded. The rendering hints and camera descriptions of a DNG source the decoder does not interpret
     * are carried over.
     *
     * @param source the raw file
     * @param decoding the decoding options
     * @param options the encoder settings
     * @param sink the output, written from its position; not closed
     * @throws MediaException.Malformed if the file is corrupt
     * @throws MediaException.Unsupported if the input is not a recognized raw format, its sensor data uses a
     * feature the decoder lacks, or it is a four-color image without color matrices
     * @throws MediaException.LimitExceeded if the image exceeds the limits or the file would exceed 4 GiB
     * @throws MediaException.Io if reading the source or writing the sink fails
     */
    public static void convert(MediaSource source, RawDecoderOptions decoding, DngEncoderOptions options,
                               MediaSink sink) {
        var file = RawDecoding.open(source, decoding.limits());
        var facts = RawDecoding.facts(file);
        if (!facts.isDecodable()) {
            throw new MediaException.Unsupported(file.format.extension(), facts.unsupported() != null
                    ? facts.unsupported() : "the file holds no sensor image");
        }
        var image = RawDecoding.sensorImage(file, decoding, true);
        var content = Guards.malformed(WHAT, () -> {
            var sensor = file.sensor(decoding);
            var result = new DngContent();
            result.width = image.width();
            result.height = image.height();
            result.channels = image.channels();
            result.samples = image.samples();
            result.cfa = image.cfa();
            result.layout = image.layout();
            result.planes = image.planes();
            result.neutral = image.neutral();
            result.balancedToXyz = image.balancedToXyz();
            if (!monochrome(result) && result.balancedToXyz.length == 0) {
                throw new MediaException.Unsupported(file.format.extension(),
                        "a four-color image without color matrices cannot be written as DNG");
            }
            if (decoding.defaultCrop()) {
                var crop = Renderer.window(sensor, image.width(), image.height(), true, false);
                if (crop[0] != 0 || crop[1] != 0 || crop[2] != image.width() || crop[3] != image.height()) {
                    result.crop = crop;
                }
                result.userCrop = sensor.userCrop;
            }
            result.opcodes3 = decoding.opcodes() ? sensor.opcodes3 : null;
            result.scaleH = sensor.scaleH;
            result.scaleV = sensor.scaleV;
            var calibration = file.color(decoding);
            if (decoding.colorMatrix().isEmpty() && calibration.colorMatrix[0] != null
                    && calibration.channels() == result.neutral.length) {
                result.calibration = calibration;
                result.baselineExposure = calibration.baselineExposure - calibration.exposureOffset;
                if (decoding.whiteBalance() instanceof RawWhiteBalance.AsShot && calibration.asShotNeutral == null) {
                    result.writeNeutral = false;
                    result.asShotWhiteXy = calibration.asShotWhiteXy;
                }
            } else {
                result.baselineExposure = calibration.baselineExposure;
            }
            if (result.calibration == null && !monochrome(result)) {
                derivable(result);
            }
            // TODO: carry the source's extra camera profiles (ExtraCameraProfiles) and its maker note
            // (DNGPrivateData, whose offsets need the fix-ups its Adobe header describes)
            var tags = new ArrayList<>(file.firstTags);
            if (result.calibration == file.color) {
                tags.addAll(file.profileTags);
            }
            result.firstTags = tags;
            result.rawTags = sensor.tags;
            return result;
        });
        content.baselineExposure = options.baselineExposure().orElse(content.baselineExposure);
        content.make = options.make().orElse(file.make);
        content.model = options.model().orElse(file.model);
        content.uniqueCameraModel = options.uniqueCameraModel().orElse(file.uniqueCameraModel != null
                ? file.uniqueCameraModel : uniqueModel(content.make, content.model));
        content.orientation = options.orientation().map(Orientation::exifCode)
                .orElse(file.orientation >= 1 && file.orientation <= 8 ? file.orientation : 1);
        content.exif = options.exif().map(exif -> exif.equals(ExifData.EMPTY) ? null : exif)
                .orElseGet(() -> sourceExif(file));
        settings(content, options);
        pad(content);
        if (options.preview()) {
            content.preview = rendering(file, decoding, facts.width(), facts.height(), options);
        }
        DngWriter.write(content, sink);
    }

    /**
     * Checks that the color description of a converted image can be derived from its matrix.
     *
     * @param content the content, with its neutral and matrix
     * @throws MediaException.Malformed if the matrix maps a neutral to a white with a component that is not
     * positive or is singular
     */
    private static void derivable(DngContent content) {
        var n = content.neutral.length;
        try {
            DngWriter.rightInverse(DngWriter.forwardMatrix(content.balancedToXyz, n), n);
        } catch (IllegalArgumentException exception) {
            throw new MediaException.Malformed(exception.getMessage(), exception);
        }
    }

    /**
     * Applies the encoder settings that do not depend on the source.
     *
     * @param content the content, updated
     * @param options the encoder settings
     */
    private static void settings(DngContent content, DngEncoderOptions options) {
        content.compression = options.compression();
        content.tileSize = options.tileSize();
        content.previewQuality = options.previewQuality();
        if (options.pixelAspectRatio().isPresent()) {
            content.scaleH = options.pixelAspectRatio().getAsDouble();
            content.scaleV = 1;
        }
    }

    /**
     * Widens a color filter array image whose default crop leaves fewer than {@link #MIN_PADDING} photosites
     * on a side, so that a converter demosaics the crop's edges from real neighbors: the side gains the smallest
     * number of photosites that is a multiple of the pattern's repeat (and of 2, which keeps staggered layouts in
     * phase) and at least {@code MIN_PADDING}, each a copy of the nearest photosite of its color and phase inside
     * the image, and the default crop keeps the image's own area. An image with opcode list 3, whose coordinates
     * refer to the stored area, is left as it is.
     *
     * @param content the content, updated
     */
    private static void pad(DngContent content) {
        var cfa = content.cfa;
        if (cfa == null || content.opcodes3 != null) {
            return;
        }
        var width = content.width;
        var height = content.height;
        var crop = content.crop != null ? content.crop : new int[]{0, 0, width, height};
        var periodX = cfa.width() % 2 == 0 ? cfa.width() : 2 * cfa.width();
        var periodY = cfa.height() % 2 == 0 ? cfa.height() : 2 * cfa.height();
        var padX = Math.ceilDiv(MIN_PADDING, periodX) * periodX;
        var padY = Math.ceilDiv(MIN_PADDING, periodY) * periodY;
        var left = crop[0] < MIN_PADDING ? padX : 0;
        var top = crop[1] < MIN_PADDING ? padY : 0;
        var right = width - crop[0] - crop[2] < MIN_PADDING ? padX : 0;
        var bottom = height - crop[1] - crop[3] < MIN_PADDING ? padY : 0;
        if (left + top + right + bottom == 0) {
            return;
        }
        var spp = content.channels;
        var paddedWidth = width + left + right;
        var paddedHeight = height + top + bottom;
        var columns = new int[paddedWidth];
        for (var x = 0; x < paddedWidth; x++) {
            columns[x] = inside(x - left, width, periodX);
        }
        var source = content.samples;
        var padded = new short[Math.multiplyExact(Math.multiplyExact(paddedWidth, paddedHeight), spp)];
        for (var y = 0; y < paddedHeight; y++) {
            var row = inside(y - top, height, periodY) * width;
            var target = y * paddedWidth * spp;
            for (var x = 0; x < paddedWidth; x++) {
                var from = (row + columns[x]) * spp;
                for (var s = 0; s < spp; s++) {
                    padded[target + x * spp + s] = source[from + s];
                }
            }
        }
        content.samples = padded;
        content.width = paddedWidth;
        content.height = paddedHeight;
        content.crop = new int[]{crop[0] + left, crop[1] + top, crop[2], crop[3]};
        content.cfa = cfa.shifted(Math.floorMod(-top, cfa.height()), Math.floorMod(-left, cfa.width()));
        content.layout = content.layout.shifted(Math.floorMod(-top, periodY), Math.floorMod(-left, periodX));
    }

    /**
     * Returns the coordinate inside an image of the same phase as one that may lie outside it.
     *
     * @param coordinate the coordinate, possibly negative or past the end
     * @param size the image's size along the axis
     * @param period the repeat of the color filter array's phase along the axis
     * @return the nearest coordinate of the same phase inside the image, or the nearest edge when the image is
     * smaller than the period
     */
    private static int inside(int coordinate, int size, int period) {
        if (coordinate >= 0 && coordinate < size) {
            return coordinate;
        }
        var moved = coordinate < 0 ? coordinate + Math.ceilDiv(-coordinate, period) * period
                : coordinate - Math.ceilDiv(coordinate - size + 1, period) * period;
        return Math.clamp(moved, 0, size - 1);
    }

    /**
     * Renders the preview of a sensor image by writing it as a DNG file without previews and rendering that
     * file with the default rendering.
     *
     * @param content the content, without a rendering
     * @param options the encoder settings
     * @return the rendering at the preview's size
     */
    private static Picture selfRendering(DngContent content, DngEncoderOptions options) {
        var compression = content.compression;
        content.compression = DngCompression.UNCOMPRESSED;
        var sink = MediaSink.memory();
        DngWriter.write(content, sink);
        content.compression = compression;
        // the file is the encoder's own, so the limits only need to admit an image of its size
        var defaults = MediaLimits.DEFAULT;
        var pixels = (long) content.width * content.height;
        var limits = defaults.toBuilder().maxDimension(Math.max(defaults.maxDimension(), Math.max(content.width,
                content.height))).maxPixels(Math.max(defaults.maxPixels(), 4 * pixels)).maxBytes(Long.MAX_VALUE)
                .build();
        var decoding = RawDecoderOptions.builder().limits(limits).build();
        var file = RawDecoding.open(MediaSource.of(sink.toByteArray()), limits);
        var facts = RawDecoding.facts(file);
        return rendering(file, decoding, facts.width(), facts.height(), options);
    }

    /**
     * Renders a raw file at the preview's size, in 8-bit sRGB.
     *
     * @param file the parsed file
     * @param decoding the rendering choices
     * @param width the width of the default rendering
     * @param height the height of the default rendering
     * @param options the encoder settings
     * @return the rendering
     */
    private static Picture rendering(RawFile file, RawDecoderOptions decoding, int width, int height,
                                     DngEncoderOptions options) {
        var side = options.previewSize().orElse(Integer.MAX_VALUE);
        var hint = DngWriter.fit(width, height, side);
        var rendering = RawDecoding.render(file, decoding.toBuilder().embeddedPreview(false)
                .colorSpace(RawColorSpace.SRGB).highBitDepth(false).minSize(hint[0], hint[1]).build());
        var target = DngWriter.fit(rendering.width(), rendering.height(), side);
        var picture = rendering.picture();
        if (picture.width() == target[0] && picture.height() == target[1]) {
            return picture;
        }
        return ImageOps.resize(picture, target[0], target[1], ResizeFilter.LANCZOS3);
    }

    /**
     * Returns the EXIF metadata of a raw file: its EXIF block, completed with the shot's settings the file records
     * elsewhere (in its first directory or its maker note): the ISO speed, exposure time, f-number, focal length,
     * capture time and lens.
     *
     * @param file the parsed file
     * @return the metadata, or {@code null} when the file has none
     */
    private static ExifData sourceExif(RawFile file) {
        var exif = ExifData.EMPTY;
        var block = RawDecoding.exif(file);
        if (block != null) {
            try {
                exif = ExifData.parse(block);
            } catch (MediaException exception) {
                // an unreadable block leaves only the settings below
            }
        }
        var entries = new LinkedHashMap<>(exif.exif());
        if (file.iso > 0 && file.iso <= 0xFFFF) {
            entries.putIfAbsent(ISO_SPEED, new ExifValue.Integers(ExifValue.Type.SHORT, new long[]{file.iso}));
        }
        putRational(entries, EXPOSURE_TIME, file.exposureTime);
        putRational(entries, F_NUMBER, file.fNumber);
        putRational(entries, FOCAL_LENGTH, file.focalLength);
        if (file.captureTime != null && !file.captureTime.isBlank()) {
            entries.putIfAbsent(DATE_TIME_ORIGINAL, new ExifValue.Ascii(file.captureTime.trim()));
        }
        if (file.lens != null && !file.lens.isBlank()) {
            entries.putIfAbsent(LENS_MODEL, new ExifValue.Ascii(file.lens.trim()));
        }
        if (entries.isEmpty() && exif.ifd0().isEmpty() && exif.gps().isEmpty()) {
            return null;
        }
        return new ExifData(exif.ifd0(), entries, exif.gps(), exif.interop(), Map.of(), Optional.empty());
    }

    /**
     * Adds a positive rational EXIF value to a directory that lacks the tag.
     *
     * @param entries the directory, updated
     * @param tag the EXIF tag number
     * @param value the value, or 0 for none
     */
    private static void putRational(Map<Integer, ExifValue> entries, int tag, double value) {
        if (!(value > 0) || !Double.isFinite(value) || entries.containsKey(tag)) {
            return;
        }
        var parts = DngWriter.rationals(new double[]{value});
        entries.put(tag, new ExifValue.Rationals(ExifValue.Type.RATIONAL, new long[]{parts[0]},
                new long[]{parts[1]}));
    }

    /**
     * Returns the unique camera model of a camera: its model, preceded by its maker when the model does not
     * start with it.
     *
     * @param make the maker, or {@code null}
     * @param model the model, or {@code null}
     * @return the unique camera model
     */
    private static String uniqueModel(String make, String model) {
        if (model == null || model.isBlank()) {
            return make != null && !make.isBlank() ? make : DEFAULT_CAMERA;
        }
        if (make == null || make.isBlank() || model.regionMatches(true, 0, make, 0, make.length())) {
            return model;
        }
        return make + " " + model;
    }

    /**
     * Returns whether content is a monochrome image: one sample per pixel and no color filter array.
     *
     * @param content the content, updated
     * @return whether it is monochrome
     */
    private static boolean monochrome(DngContent content) {
        return content.cfa == null && content.channels == 1;
    }
}
