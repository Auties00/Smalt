package com.github.auties00.smalt.image.avif.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.AnimationFrame;
import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.AuxiliaryImage;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.gainmap.GainMap;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.anim.SeekableAnimationReader;
import com.github.auties00.smalt.internal.gainmap.HeifToneMaps;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.internal.heif.HeifGeometry;
import com.github.auties00.smalt.internal.heif.HeifItem;
import com.github.auties00.smalt.internal.heif.HeifMovie;
import com.github.auties00.smalt.internal.heif.HeifPictures;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.internal.heif.HeifStill;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.DataReferenceResolver;
import com.github.auties00.smalt.io.MediaSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Implements the operations of {@link com.github.auties00.smalt.image.avif.AvifDecoder}: chooses
 * between the primary image and the image sequence of a file, picks a thumbnail when the caller
 * asks for a reduced size, and summarizes, decodes or plays the image chosen.
 *
 * <p>A file whose major brand is {@code avis} shows its sequence; a file whose major brand is
 * {@code avif} shows its primary image; any other file shows its sequence when it has one. An
 * animation is opened over the sequence whenever the file has one, and over the shown still image
 * as a single frame otherwise.
 *
 * <p>The still image shown is the primary item, or the first sample transform item ({@code sato})
 * the alternatives group ({@code altr}) of the primary item lists before it that has data, is not a
 * thumbnail and that this decoder reconstructs; a sample transform that uses a feature this
 * decoder lacks leaves the primary item shown. The ICC profile, the EXIF block and the XMP packet
 * are those of the primary item, or for the ICC profile and the EXIF block, of the first input of
 * each derived image down to a coded item when the item has none.
 *
 * @implNote This implementation chooses the source as libavif's {@code AVIF_DECODER_SOURCE_AUTO}
 * does ({@code avifDecoderReset}, {@code src/read.c}, BSD 2-clause), and the sample transform item
 * as its {@code avifDecoderDataFindSampleTransformImageItem} does.
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
        return parse(source, limits, DataReferenceResolver.none());
    }

    /**
     * Parses an AVIF file whose items may be stored in the files its data references name.
     *
     * @param source the file
     * @param limits the bounds
     * @param resolver the resolver of the files the data references name
     * @return the parsed file
     * @throws MediaException.Unsupported if the file has neither the {@code avif} nor the
     * {@code avis} brand
     */
    private static HeifFile parse(MediaSource source, MediaLimits limits, DataReferenceResolver resolver) {
        var file = HeifFile.parse(source, limits, true, resolver);
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
     * Returns the primary item of a file.
     *
     * @param file the parsed file
     * @return the primary item
     * @throws MediaException.Malformed if the file has no primary item
     */
    private static HeifItem primary(HeifFile file) {
        var primary = file.primaryItem();
        if (primary == null) {
            throw new MediaException.Malformed("avif: the file has no primary item");
        }
        return primary;
    }

    /**
     * Returns the still image item a file shows in place of its primary item.
     *
     * @param source the file
     * @param file the parsed file
     * @param primary the primary item
     * @param limits the bounds
     * @return the first sample transform item preferred to the primary item that has data, is not a
     * thumbnail and that this decoder reconstructs, or {@code primary}
     * @throws MediaException.Malformed if such a sample transform item is malformed
     */
    private static HeifItem shown(MediaSource source, HeifFile file, HeifItem primary, MediaLimits limits) {
        for (var candidate : file.preferredAlternatives(primary.id())) {
            if (!candidate.type().equals("sato") || candidate.references("thmb").length > 0 || !candidate.isLocated()
                    || candidate.size() == 0) {
                continue;
            }
            try {
                Reconstruction.requireDecodable(source, file, candidate, limits);
                return candidate;
            } catch (MediaException.Unsupported exception) {
                // a reader skips an alternative it cannot reconstruct
            }
        }
        return primary;
    }

    /**
     * Summarizes an AVIF file without decoding pixels.
     *
     * @param source the file
     * @param limits the bounds
     * @return the summary; the size is the size after the clean aperture and before the
     * orientation, which comes from the transformative properties of the shown item or
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
        var primary = primary(file);
        return stillInfo(source, file, shown(source, file, primary, limits), limits);
    }

    /**
     * Summarizes a still image item.
     *
     * @param source the file
     * @param file the parsed file
     * @param item the item the file shows
     * @param limits the bounds
     * @return the summary
     * @throws MediaException.Malformed if the item declares no valid size or is inconsistent
     * @throws MediaException.Unsupported if the item cannot be reconstructed
     */
    private static ImageInfo stillInfo(MediaSource source, HeifFile file, HeifItem item, MediaLimits limits) {
        Reconstruction.requireDecodable(source, file, item, limits);
        var geometry = geometry(source, file, item, limits);
        return new ImageInfo(ImageFormat.AVIF, geometry.width(), geometry.height(),
                Reconstruction.hasAlpha(source, file, item, limits), false, 1, geometry.orientation());
    }

    /**
     * Resolves the crop and orientation of an image item from its declared size.
     *
     * @param source the file
     * @param file the parsed file
     * @param item the image item
     * @param limits the bounds
     * @return the geometry
     * @throws MediaException.Malformed if the item declares no valid size
     */
    private static HeifGeometry geometry(MediaSource source, HeifFile file, HeifItem item, MediaLimits limits) {
        var size = Reconstruction.declaredSize(source, file, item, limits);
        return HeifGeometry.resolve(item.properties(), size[0], size[1]);
    }

    /**
     * Decodes the image an AVIF file shows.
     *
     * @param source the file
     * @param options the limits, the bit depth choice and the reduced-size hint
     * @return the decoded image
     */
    public static DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        var limits = options.limits();
        var highBitDepth = options.highBitDepth();
        var file = parse(source, limits, options.dataReferences());
        if (showsSequence(file)) {
            try (var reader = new SequenceReader(source, file, limits, highBitDepth)) {
                var picture = reader.readFirstPicture(highBitDepth);
                return new DecodedImage(picture, ImageFormat.AVIF, reader.orientation(), picture.width(),
                        picture.height(), Optional.empty(), Optional.empty(), HeifPictures.colorProfile(null, picture));
            }
        }
        var primary = primary(file);
        var shown = shown(source, file, primary, limits);
        var icc = icc(file, primary);
        var embeddedProfile = icc == null ? null : ParallelBands.submit(() -> HeifPictures.colorProfile(icc, null));
        HeifStill result = null;
        HeifGeometry full = null;
        try {
            if (options.minWidth() > 0 || options.minHeight() > 0) {
                try {
                    full = geometry(source, file, shown, limits);
                } catch (MediaException.Io exception) {
                    throw exception;
                } catch (MediaException exception) {
                    full = null;
                }
            }
            if (full != null) {
                for (var thumbnail : thumbnails(source, file, primary, full, limits, options)) {
                    try {
                        result = Reconstruction.decode(source, file, thumbnail, limits, highBitDepth);
                        break;
                    } catch (MediaException.Malformed | MediaException.Unsupported exception) {
                        result = null;
                    }
                }
            }
            if (result == null) {
                full = null;
                result = decodeShown(source, file, primary, shown, limits, highBitDepth);
            }
        } catch (RuntimeException | Error failure) {
            if (embeddedProfile != null) {
                embeddedProfile.cancel();
            }
            throw failure;
        }
        var picture = result.picture();
        var sourceWidth = full == null ? picture.width() : full.width();
        var sourceHeight = full == null ? picture.height() : full.height();
        var orientation = full == null ? result.geometry().orientation() : full.orientation();
        return new DecodedImage(picture, ImageFormat.AVIF, orientation, sourceWidth, sourceHeight,
                Optional.ofNullable(icc == null ? null : icc.clone()),
                Optional.ofNullable(exif(source, file, primary, limits)),
                Optional.ofNullable(file.xmpOf(source, primary, limits)),
                embeddedProfile == null ? HeifPictures.colorProfile(null, picture) : embeddedProfile.join());
    }

    /**
     * Reconstructs the shown item, or the primary item when the shown sample transform turns out
     * to use a feature this decoder lacks.
     *
     * @param source the file
     * @param file the parsed file
     * @param primary the primary item
     * @param shown the item the file shows
     * @param limits the bounds
     * @param highBitDepth whether a source of more than 8 bits keeps its depth
     * @return the image
     */
    private static HeifStill decodeShown(MediaSource source, HeifFile file, HeifItem primary, HeifItem shown,
                                         MediaLimits limits, boolean highBitDepth) {
        if (shown != primary) {
            try {
                return Reconstruction.decode(source, file, shown, limits, highBitDepth);
            } catch (MediaException.Unsupported exception) {
                // the primary item is the alternative every reader can show
            }
        }
        return Reconstruction.decode(source, file, primary, limits, highBitDepth);
    }

    /**
     * Lists the thumbnails that may be decoded instead of the primary image under a reduced-size
     * hint, smallest first.
     *
     * <p>A thumbnail qualifies when its size after its clean aperture is at least the hint and
     * smaller than the shown image's, its orientation is the shown image's, and its aspect ratio
     * is the shown image's within two pixels of rounding.
     *
     * @param source the file
     * @param file the parsed file
     * @param primary the primary item, which the thumbnails reference
     * @param full the geometry of the shown item
     * @param limits the bounds
     * @param options the decoding options, with a reduced-size hint
     * @return the qualifying thumbnail items, empty to decode the shown image
     */
    private static List<HeifItem> thumbnails(MediaSource source, HeifFile file, HeifItem primary, HeifGeometry full,
                                             MediaLimits limits, ImageDecoderOptions options) {
        var found = new ArrayList<HeifItem>();
        var areas = new HashMap<HeifItem, Long>();
        for (var candidate : file.thumbnailsOf(primary.id())) {
            HeifGeometry geometry;
            try {
                Reconstruction.requireDecodable(source, file, candidate, limits);
                geometry = geometry(source, file, candidate, limits);
            } catch (MediaException.Io exception) {
                throw exception;
            } catch (MediaException exception) {
                continue;
            }
            var width = geometry.width();
            var height = geometry.height();
            var matches = geometry.orientation() == full.orientation()
                    && width >= options.minWidth() && height >= options.minHeight()
                    && width <= full.width() && height <= full.height()
                    && Math.abs((long) width * full.height() - (long) height * full.width())
                    <= 2L * Math.max(full.width(), full.height());
            var area = (long) width * height;
            if (matches && area < (long) full.width() * full.height()) {
                found.add(candidate);
                areas.put(candidate, area);
            }
        }
        found.sort(Comparator.comparingLong(areas::get));
        return found;
    }

    /**
     * Returns the ICC profile of an image item.
     *
     * @param file the parsed file
     * @param item the image item
     * @return the profile bytes of its {@code colr} property of type {@code prof} or {@code rICC},
     * or of the first input of each derived image down to a coded item that has one, or
     * {@code null}
     */
    private static byte[] icc(HeifFile file, HeifItem item) {
        var current = item;
        for (var depth = 0; current != null && depth <= Reconstruction.MAX_DEPTH; depth++) {
            var icc = current.property(HeifProperty.ColorIcc.class);
            if (icc != null) {
                return icc.profile();
            }
            var inputs = current.references("dimg");
            current = current.type().equals("grid") || inputs.length == 0 ? null : file.item(inputs[0]);
        }
        return null;
    }

    /**
     * Reads the EXIF metadata that describes an image item, or the first input of each derived
     * image down to a coded item when the item itself has none.
     *
     * <p>Metadata is not needed to show the image, so an {@code Exif} item that is corrupt or holds
     * no TIFF structure is ignored.
     *
     * @param source the file
     * @param file the parsed file
     * @param item the image item
     * @param limits the bounds
     * @return the TIFF structure, or {@code null} when there is none
     */
    private static byte[] exif(MediaSource source, HeifFile file, HeifItem item, MediaLimits limits) {
        var current = item;
        for (var depth = 0; current != null && depth <= Reconstruction.MAX_DEPTH; depth++) {
            for (var description : file.descriptionsOf(current.id())) {
                if (!description.type().equals("Exif") || !description.isLocated()) {
                    continue;
                }
                try {
                    var tiff = HeifFile.exifTiff(file.readItem(source, description, limits));
                    if (tiff != null) {
                        return tiff;
                    }
                } catch (MediaException.Malformed | MediaException.LimitExceeded exception) {
                    return null;
                }
            }
            var inputs = current.references("dimg");
            current = current.type().equals("grid") || inputs.length == 0 ? null : file.item(inputs[0]);
        }
        return null;
    }

    /**
     * Decodes the gain map of the primary image of an AVIF file: the second input of its {@code tmap} item, with
     * the metadata of that item, as {@link HeifToneMaps} finds them.
     *
     * @param source the file
     * @param options the limits and the bit depth choice
     * @return the gain map, or empty when the file shows a sequence or has no tone map item that counts
     */
    public static Optional<GainMap> decodeGainMap(MediaSource source, ImageDecoderOptions options) {
        var limits = options.limits();
        var file = parse(source, limits, options.dataReferences());
        if (showsSequence(file) || file.primaryItem() == null) {
            return Optional.empty();
        }
        var toneMap = HeifToneMaps.find(source, file, file.primaryItem(), limits, true);
        if (toneMap == null) {
            return Optional.empty();
        }
        var still = Reconstruction.decode(source, file, toneMap.gainMap(), limits, options.highBitDepth());
        return Optional.of(new GainMap(toneMap.metadata(), still.picture(), toneMap.alternateProfile(),
                toneMap.alternateLightLevel()));
    }

    /**
     * Lists the auxiliary images of the primary image of an AVIF file, those this decoder reconstructs.
     *
     * @param source the file
     * @param limits the bounds
     * @return the images, empty for a file that shows a sequence
     */
    public static List<AuxiliaryImage> auxiliaryImages(MediaSource source, MediaLimits limits) {
        return listed(source, parse(source, limits), limits).stream().map(Listed::description).toList();
    }

    /**
     * Decodes an auxiliary image of the primary image of an AVIF file.
     *
     * @param source the file
     * @param index the index of the image in the list of {@link #auxiliaryImages(MediaSource, MediaLimits)}
     * @param options the limits and the bit depth choice
     * @return the image, with the orientation of its own transformative properties and the color of its own
     * {@code colr} property, or {@code null} when the file lists no image of that index
     */
    public static DecodedImage decodeAuxiliaryImage(MediaSource source, int index, ImageDecoderOptions options) {
        var limits = options.limits();
        var file = parse(source, limits, options.dataReferences());
        var listed = listed(source, file, limits);
        if (index < 0 || index >= listed.size()) {
            return null;
        }
        var item = listed.get(index).item();
        var still = Reconstruction.decode(source, file, item, limits, options.highBitDepth());
        var picture = still.picture();
        var icc = item.property(HeifProperty.ColorIcc.class);
        var profile = icc == null ? null : icc.profile();
        return new DecodedImage(picture, ImageFormat.AVIF, still.geometry().orientation(), picture.width(),
                picture.height(), Optional.ofNullable(profile == null ? null : profile.clone()), Optional.empty(),
                Optional.empty(), HeifPictures.colorProfile(profile, picture));
    }

    /**
     * An auxiliary image with its description.
     *
     * @param item the image item
     * @param description its description in the list
     */
    private record Listed(HeifItem item, AuxiliaryImage description) {
    }

    /**
     * Lists the auxiliary images of the primary image that this decoder reconstructs and whose size is declared.
     *
     * @param source the file
     * @param file the parsed file
     * @param limits the bounds
     * @return the images
     */
    private static List<Listed> listed(MediaSource source, HeifFile file, MediaLimits limits) {
        if (showsSequence(file) || file.primaryItem() == null) {
            return List.of();
        }
        var result = new ArrayList<Listed>();
        for (var auxiliary : HeifToneMaps.auxiliaries(source, file, file.primaryItem(), limits, true)) {
            int[] size;
            try {
                Reconstruction.requireDecodable(source, file, auxiliary.item(), limits);
                size = Reconstruction.declaredSize(source, file, auxiliary.item(), limits);
            } catch (MediaException.Io exception) {
                throw exception;
            } catch (MediaException exception) {
                continue;
            }
            if (size[0] <= 0 || size[1] <= 0) {
                continue;
            }
            result.add(new Listed(auxiliary.item(), new AuxiliaryImage(result.size(), auxiliary.type(),
                    auxiliary.identifier(), size[0], size[1])));
        }
        return result;
    }

    /**
     * Opens an AVIF file as an animation.
     *
     * @param source the file
     * @param limits the bounds
     * @param highBitDepth whether frames of a source deeper than 8 bits are 16-bit RGBA
     * @return the reader: the sequence when the file has one, otherwise the shown still image as
     * one frame shown for zero microseconds and played once; both can seek
     */
    public static AnimationReader openAnimation(MediaSource source, MediaLimits limits, boolean highBitDepth) {
        var file = parse(source, limits);
        if (file.hasMovie() && (showsSequence(file) || hasColorTrack(source, file, limits))) {
            return new SequenceReader(source, file, limits, highBitDepth);
        }
        var primary = primary(file);
        var shown = shown(source, file, primary, limits);
        var info = stillInfo(source, file, shown, limits);
        var frame = new Supplier<Picture>() {
            private Picture decoded;

            @Override
            public Picture get() {
                if (decoded == null) {
                    var picture = decodeShown(source, file, primary, shown, limits, highBitDepth).picture();
                    decoded = picture instanceof Yuv16Picture || picture instanceof Rgba16Picture
                            ? ImageOps.toRgba16(picture) : ImageOps.toRgba(picture);
                }
                return decoded;
            }
        };
        return SeekableAnimationReader.of(() -> new StillReader(info.width(), info.height(), frame));
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
         * Decodes the image, once for every reader sharing it.
         */
        private final Supplier<Picture> decoder;

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
        private StillReader(int width, int height, Supplier<Picture> decoder) {
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
