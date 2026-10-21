package com.github.auties00.smalt.image.avif.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.AnimationFrame;
import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.internal.heif.HeifGeometry;
import com.github.auties00.smalt.internal.heif.HeifMovie;
import com.github.auties00.smalt.internal.heif.HeifPictures;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.internal.heif.HeifStill;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSource;

import java.util.Optional;

/**
 * Implements the operations of {@link com.github.auties00.smalt.image.avif.AvifDecoder}: chooses
 * between the primary image and the image sequence of a file, and summarizes, decodes or plays
 * the one chosen.
 *
 * <p>A file whose major brand is {@code avis} shows its sequence; a file whose major brand is
 * {@code avif} shows its primary image; any other file shows its sequence when it has one. An
 * animation is opened over the sequence whenever the file has one, and over the primary image as
 * a single frame otherwise.
 *
 * @implNote This implementation chooses the source as libavif's {@code AVIF_DECODER_SOURCE_AUTO}
 * does ({@code avifDecoderReset}, {@code src/read.c}, BSD 2-clause).
 */
public final class AvifFiles {
    /**
     * Prevents instantiation.
     */
    private AvifFiles() {
        throw new AssertionError();
    }

    /**
     * Parses the structure of an AVIF file.
     *
     * @param source the file
     * @param limits the bounds
     * @return the parsed file
     * @throws MediaException.Unsupported if the file has neither the {@code avif} nor the
     * {@code avis} brand
     */
    private static HeifFile parse(MediaSource source, MediaLimits limits) {
        var file = HeifFile.parse(source, limits);
        if (!file.hasBrand("avif") && !file.hasBrand("avis")) {
            throw new MediaException.Unsupported("avif", "the file has neither the avif nor the avis brand");
        }
        return file;
    }

    /**
     * Returns whether the file shows its image sequence rather than its primary image.
     *
     * @param file the parsed file
     * @return {@code true} to show the sequence
     */
    private static boolean showsSequence(HeifFile file) {
        if (!file.hasMovie()) {
            return false;
        }
        return switch (file.majorBrand()) {
            case "avis" -> true;
            case "avif" -> file.primaryItem() == null;
            default -> true;
        };
    }

    /**
     * Summarizes an AVIF file without decoding pixels.
     *
     * @param source the file
     * @param limits the bounds
     * @return the summary; the size is the size after the clean aperture and before the
     * orientation, which comes from the transformative properties of the primary item or
     * from the track matrix of a sequence
     */
    public static ImageInfo probe(MediaSource source, MediaLimits limits) {
        var file = parse(source, limits);
        if (showsSequence(file)) {
            var movie = HeifMovie.parse(source, file, limits);
            var color = SequenceReader.colorTrack(movie);
            if (color == null) {
                throw new MediaException.Malformed("avif: the sequence has no AV1 color track");
            }
            var frames = (int) Math.min(Integer.MAX_VALUE, color.sampleCount());
            var width = color.width();
            var height = color.height();
            if (width <= 0 || height <= 0) {
                throw new MediaException.Malformed("avif: the color track has no size");
            }
            Orientation orientation;
            try (var reader = new SequenceReader(source, file, limits, false)) {
                orientation = reader.orientation();
            }
            return new ImageInfo(ImageFormat.AVIF, width, height, SequenceReader.alphaTrack(movie, color.id()) != null,
                    frames > 1, frames, orientation);
        }
        var plan = StillPlan.of(source, file, limits);
        long width;
        long height;
        if (plan.grid() != null) {
            width = plan.grid().outputWidth();
            height = plan.grid().outputHeight();
        } else {
            var extent = plan.primary().property(HeifProperty.SpatialExtent.class);
            if (extent == null) {
                throw new MediaException.Malformed("avif: the primary item has no ispe property");
            }
            width = extent.width();
            height = extent.height();
        }
        if (width <= 0 || height <= 0 || width > Integer.MAX_VALUE || height > Integer.MAX_VALUE) {
            throw new MediaException.Malformed("avif: invalid image size " + width + "x" + height);
        }
        var geometry = HeifGeometry.resolve(plan.primary().properties(), (int) width, (int) height);
        return new ImageInfo(ImageFormat.AVIF, geometry.width(), geometry.height(), plan.hasAlpha(), false, 1,
                geometry.orientation());
    }

    /**
     * Decodes the image an AVIF file shows.
     *
     * @param source the file
     * @param limits the bounds
     * @param highBitDepth whether a source of more than 8 bits keeps its depth
     * @return the decoded image
     */
    public static DecodedImage decode(MediaSource source, MediaLimits limits, boolean highBitDepth) {
        var file = parse(source, limits);
        if (showsSequence(file)) {
            try (var reader = new SequenceReader(source, file, limits, highBitDepth)) {
                var picture = reader.readFirstPicture(highBitDepth);
                return new DecodedImage(picture, ImageFormat.AVIF, reader.orientation(), picture.width(),
                        picture.height(), Optional.empty(), Optional.empty(), HeifPictures.colorProfile(null, picture));
            }
        }
        var plan = StillPlan.of(source, file, limits);
        var icc = plan.primary().property(HeifProperty.ColorIcc.class);
        var embedded = icc == null ? null : icc.profile();
        var embeddedProfile = embedded == null ? null
                : ParallelBands.submit(() -> HeifPictures.colorProfile(embedded, null));
        HeifStill result;
        try {
            result = StillDecoder.decode(source, file, plan, limits, highBitDepth);
        } catch (RuntimeException | Error failure) {
            if (embeddedProfile != null) {
                embeddedProfile.cancel();
            }
            throw failure;
        }
        var picture = result.picture();
        return new DecodedImage(picture, ImageFormat.AVIF, result.geometry().orientation(), picture.width(),
                picture.height(), Optional.ofNullable(embedded == null ? null : embedded.clone()),
                Optional.ofNullable(exif(source, file, plan, limits)),
                Optional.ofNullable(file.xmpOf(source, plan.primary(), limits)),
                embeddedProfile == null ? HeifPictures.colorProfile(null, picture) : embeddedProfile.join());
    }

    /**
     * Reads the EXIF metadata that describes the primary item.
     *
     * <p>Metadata is not needed to show the image, so an {@code Exif} item that is corrupt or holds
     * no TIFF structure is ignored.
     *
     * @param source the file
     * @param file the parsed file
     * @param plan the reconstruction plan
     * @param limits the bounds
     * @return the TIFF structure, or {@code null} when there is none
     */
    private static byte[] exif(MediaSource source, HeifFile file, StillPlan plan, MediaLimits limits) {
        for (var item : file.descriptionsOf(plan.primary().id())) {
            if (!item.type().equals("Exif") || !item.isLocated()) {
                continue;
            }
            try {
                var tiff = HeifFile.exifTiff(file.readItem(source, item, limits));
                if (tiff != null) {
                    return tiff;
                }
            } catch (MediaException.Malformed exception) {
                return null;
            }
        }
        return null;
    }

    /**
     * Opens an AVIF file as an animation.
     *
     * @param source the file
     * @param limits the bounds
     * @param highBitDepth whether frames of a source deeper than 8 bits are 16-bit RGBA
     * @return the reader: the sequence when the file has one, otherwise the primary image as one
     * frame shown for zero microseconds and played once
     */
    public static AnimationReader openAnimation(MediaSource source, MediaLimits limits, boolean highBitDepth) {
        var file = parse(source, limits);
        if (file.hasMovie() && (showsSequence(file) || hasColorTrack(source, file, limits))) {
            return new SequenceReader(source, file, limits, highBitDepth);
        }
        var plan = StillPlan.of(source, file, limits);
        var info = probe(source, limits);
        return new StillReader(info.width(), info.height(), () -> {
            var picture = StillDecoder.decode(source, file, plan, limits, highBitDepth).picture();
            return picture instanceof Yuv16Picture || picture instanceof Rgba16Picture ? ImageOps.toRgba16(picture)
                    : ImageOps.toRgba(picture);
        });
    }

    /**
     * Returns whether the sequence of a file has an AV1 color track.
     *
     * @param source the file
     * @param file the parsed file
     * @param limits the bounds
     * @return {@code true} when a color track exists
     */
    private static boolean hasColorTrack(MediaSource source, HeifFile file, MediaLimits limits) {
        try {
            return SequenceReader.colorTrack(HeifMovie.parse(source, file, limits)) != null;
        } catch (MediaException.Malformed exception) {
            return false;
        }
    }

    /**
     * Presents one still image as an animation of one frame, decoding it on the first read.
     */
    private static final class StillReader implements AnimationReader {
        /**
         * The canvas width.
         */
        private final int width;

        /**
         * The canvas height.
         */
        private final int height;

        /**
         * Decodes the image.
         */
        private final java.util.function.Supplier<Picture> decoder;

        /**
         * Whether the frame was read.
         */
        private boolean consumed;

        /**
         * Constructs the reader.
         *
         * @param width the image width
         * @param height the image height
         * @param decoder decodes the image
         */
        private StillReader(int width, int height, java.util.function.Supplier<Picture> decoder) {
            this.width = width;
            this.height = height;
            this.decoder = decoder;
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }

        @Override
        public int loopCount() {
            return 1;
        }

        @Override
        public int frameCount() {
            return 1;
        }

        @Override
        public AnimationFrame read() {
            if (consumed) {
                return null;
            }
            consumed = true;
            return new AnimationFrame(decoder.get(), 0, 0);
        }
    }
}
