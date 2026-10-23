package com.github.auties00.smalt.image.heic.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.AnimationFrame;
import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.anim.SeekableAnimationReader;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.internal.heif.HeifGeometry;
import com.github.auties00.smalt.internal.heif.HeifGrid;
import com.github.auties00.smalt.internal.heif.HeifItem;
import com.github.auties00.smalt.internal.heif.HeifMovie;
import com.github.auties00.smalt.internal.heif.HeifOverlay;
import com.github.auties00.smalt.internal.heif.HeifPictures;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.internal.heif.HeifStill;
import com.github.auties00.smalt.io.MediaSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Implements the operations of {@link com.github.auties00.smalt.image.heic.HeicDecoder}: chooses
 * between the primary image and the image sequence of a file, picks a thumbnail when the caller
 * asks for a reduced size, and summarizes, decodes or plays the image chosen.
 *
 * <p>A file shows its primary item when it has one, the image HEIF designates for display, and
 * the first frame of its image sequence otherwise. In place of the primary item it shows the
 * first item its alternatives group ({@code altr}) lists before it that is not a thumbnail and
 * that this decoder reconstructs, such as the layered HEVC enhancement of an HEVC or AVC base
 * image; when that item turns out to be corrupt or to use a feature this decoder lacks, the
 * primary item is shown after all. An
 * animation is opened over the sequence whenever the file has an HEVC or AVC color track, and over the
 * primary image as a single frame otherwise.
 */
public final class HeicFiles {
    /**
     * The deepest chain of derived images followed when looking for the color and metadata of a
     * derived primary item.
     */
    private static final int MAX_LINEAGE = StillDecoder.MAX_DEPTH;

    /**
     * Prevents instantiation.
     */
    private HeicFiles() {
        throw new AssertionError();
    }

    /**
     * Returns whether the file shows its image sequence rather than its primary image.
     *
     * @param file the parsed file
     * @return {@code true} when the file has a sequence and no primary item
     */
    private static boolean showsSequence(HeifFile file) {
        return file.hasMovie() && file.primaryItem() == null;
    }

    /**
     * Returns whether the sequence of a file has an HEVC or AVC color track.
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
     * Returns the primary item of a file.
     *
     * @param file the parsed file
     * @return the primary item
     * @throws MediaException.Malformed if the file has no primary item
     */
    private static HeifItem primary(HeifFile file) {
        var primary = file.primaryItem();
        if (primary == null) {
            throw new MediaException.Malformed("heic: the file has neither a primary item nor an image sequence");
        }
        return primary;
    }

    /**
     * Returns the still image item a file shows in place of its primary item.
     *
     * @param file the parsed file
     * @param primary the primary item
     * @return the first item the alternatives group of the primary item lists before it that is
     * not a thumbnail and that this decoder reconstructs, or {@code primary}
     */
    private static HeifItem shown(HeifFile file, HeifItem primary) {
        for (var candidate : file.preferredAlternatives(primary.id())) {
            if (candidate.references("thmb").length > 0) {
                continue;
            }
            try {
                StillDecoder.requireDecodable(candidate);
                return candidate;
            } catch (MediaException.Unsupported exception) {
                // a reader skips an alternative it cannot reconstruct
            }
        }
        return primary;
    }

    /**
     * Reconstructs the item a file shows in place of its primary item, or the primary item when
     * that one cannot be reconstructed.
     *
     * @param source the file
     * @param file the parsed file
     * @param primary the primary item
     * @param limits the bounds of every item and picture
     * @param highBitDepth whether a source of more than 8 bits keeps its depth
     * @return the image
     */
    private static HeifStill decodeShown(MediaSource source, HeifFile file, HeifItem primary, MediaLimits limits,
                                         boolean highBitDepth) {
        var shown = shown(file, primary);
        if (shown != primary) {
            try {
                return StillDecoder.decode(source, file, shown, limits, highBitDepth);
            } catch (MediaException.Malformed | MediaException.Unsupported exception) {
                // the primary item is the alternative every reader can show
            }
        }
        return StillDecoder.decode(source, file, primary, limits, highBitDepth);
    }

    /**
     * Summarizes a HEIC file without decoding pixels.
     *
     * @param source the file
     * @param limits the bounds
     * @return the summary; the size is the size after the clean aperture and before the
     * orientation
     * @throws MediaException.Malformed if the file structure is invalid or truncated
     * @throws MediaException.Unsupported if the file uses a feature this decoder lacks
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits
     */
    public static ImageInfo probe(MediaSource source, MediaLimits limits) {
        var file = HeifFile.parse(source, limits, false);
        if (showsSequence(file)) {
            var movie = HeifMovie.parse(source, file, limits);
            var color = SequenceReader.requireColorTrack(movie);
            var frames = (int) Math.min(Integer.MAX_VALUE, color.sampleCount());
            Orientation orientation;
            int width;
            int height;
            try (var reader = new SequenceReader(source, file, limits, false)) {
                orientation = reader.orientation();
                width = reader.width();
                height = reader.height();
            }
            return new ImageInfo(ImageFormat.HEIC, width, height, SequenceReader.alphaTrack(movie, color.id()) != null,
                    frames > 1, frames, orientation);
        }
        var primary = primary(file);
        return stillInfo(source, file, shown(file, primary), limits);
    }

    /**
     * Summarizes a still image item.
     *
     * @param source the file
     * @param file the parsed file
     * @param primary the item the file shows
     * @param limits the bounds
     * @return the summary
     * @throws MediaException.Malformed if the item declares no valid size
     * @throws MediaException.Unsupported if the item cannot be reconstructed
     */
    private static ImageInfo stillInfo(MediaSource source, HeifFile file, HeifItem primary, MediaLimits limits) {
        StillDecoder.requireDecodable(primary);
        var geometry = geometry(source, file, primary, limits);
        return new ImageInfo(ImageFormat.HEIC, geometry.width(), geometry.height(), hasAlpha(file, primary), false, 1,
                geometry.orientation());
    }

    /**
     * Returns whether an image item has an alpha plane or is composed from images that may have
     * one.
     *
     * @param file the parsed file
     * @param item the image item
     * @return {@code true} when the item or a grid tile carries alpha of its own, an alpha auxiliary
     * image is found for the item or for every tile of a grid, or for an overlay
     */
    private static boolean hasAlpha(HeifFile file, HeifItem item) {
        if (StillDecoder.alphaOf(file, item) != null || StillDecoder.hasOwnAlpha(item) || item.type().equals("iovl")) {
            return true;
        }
        if (item.type().equals("grid")) {
            var ids = item.references("dimg");
            if (ids.length == 0) {
                return false;
            }
            var every = true;
            for (var id : ids) {
                var tile = file.item(id);
                if (tile != null && StillDecoder.hasOwnAlpha(tile)) {
                    return true;
                }
                every &= tile != null && StillDecoder.alphaOf(file, tile) != null;
            }
            return every;
        }
        return false;
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
        long width;
        long height;
        var extent = item.property(HeifProperty.SpatialExtent.class);
        if (extent != null) {
            width = extent.width();
            height = extent.height();
        } else if (item.type().equals("grid") && item.size() <= 12) {
            var grid = HeifGrid.parse(file.readItem(source, item, limits));
            width = grid.outputWidth();
            height = grid.outputHeight();
        } else if (item.type().equals("iovl") && item.size() <= 20 + 8L * item.references("dimg").length) {
            var overlay = HeifOverlay.parse(file.readItem(source, item, limits), item.references("dimg").length);
            width = overlay.outputWidth();
            height = overlay.outputHeight();
        } else {
            throw new MediaException.Malformed("heic: item " + item.id() + " has no ispe property");
        }
        if (width <= 0 || height <= 0 || width > Integer.MAX_VALUE || height > Integer.MAX_VALUE) {
            throw new MediaException.Malformed("heic: invalid image size " + width + "x" + height);
        }
        return HeifGeometry.resolve(item.properties(), (int) width, (int) height);
    }

    /**
     * Decodes the image a HEIC file shows.
     *
     * @param source the file
     * @param options the limits, the bit depth choice, the reduced-size hint and the color
     * conversion
     * @return the decoded image
     */
    public static DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        var limits = options.limits();
        var file = HeifFile.parse(source, limits, false);
        DecodedImage image;
        if (showsSequence(file)) {
            try (var reader = new SequenceReader(source, file, limits, options.highBitDepth())) {
                var picture = reader.readFirstPicture(options.highBitDepth());
                image = new DecodedImage(picture, ImageFormat.HEIC, reader.orientation(), picture.width(),
                        picture.height(), Optional.empty(), Optional.empty(), HeifPictures.colorProfile(null, picture));
            }
        } else {
            image = decodeStill(source, file, primary(file), options);
        }
        return options.convertToSrgb() ? ImageOps.toSrgb(image) : image;
    }

    /**
     * Decodes one view of the stereo pair a HEIC file shows.
     *
     * <p>The pair is the first stereo pair entity group ({@code ster}, ISO/IEC 23008-12) of two
     * entities that holds the primary item or the item the file shows in its place; its first
     * entity is the left view and its second the right view. The view decodes as
     * {@link #decode} decodes the primary item: the item its alternatives group prefers when that
     * one can be reconstructed, a thumbnail under a reduced-size hint, and its own metadata.
     *
     * @param source the file
     * @param view 0 for the left view, 1 for the right view
     * @param options the limits, the bit depth choice, the reduced-size hint and the color
     * conversion
     * @return the decoded view, or empty when the file has no primary item or its primary item
     * belongs to no stereo pair
     * @throws MediaException.Malformed if the file is invalid, corrupt or truncated, or the pair
     * names an item the file lacks
     * @throws MediaException.Unsupported if the view uses a feature this decoder lacks
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits
     */
    public static Optional<DecodedImage> decodeStereoView(MediaSource source, int view, ImageDecoderOptions options) {
        var file = HeifFile.parse(source, options.limits(), false);
        var primary = file.primaryItem();
        if (primary == null) {
            return Optional.empty();
        }
        var shown = shown(file, primary);
        for (var group : file.entityGroups()) {
            var entities = group.entities();
            if (!group.type().equals("ster") || entities.length != 2
                    || !holds(entities, primary.id()) && !holds(entities, shown.id())) {
                continue;
            }
            var item = file.item(entities[view]);
            if (item == null) {
                throw new MediaException.Malformed("heic: the stereo pair " + group.id() + " names the missing item "
                        + entities[view]);
            }
            var image = decodeStill(source, file, item, options);
            return Optional.of(options.convertToSrgb() ? ImageOps.toSrgb(image) : image);
        }
        return Optional.empty();
    }

    /**
     * Returns whether an entity group lists an item.
     *
     * @param entities the entity identifiers of the group
     * @param id the item identifier
     * @return {@code true} when one of {@code entities} is {@code id}
     */
    private static boolean holds(int[] entities, int id) {
        for (var entity : entities) {
            if (entity == id) {
                return true;
            }
        }
        return false;
    }

    /**
     * Decodes a still image item, or one of its thumbnails when the reduced-size hint allows.
     *
     * @param source the file
     * @param file the parsed file
     * @param primary the primary item
     * @param options the decoding options
     * @return the decoded image
     */
    private static DecodedImage decodeStill(MediaSource source, HeifFile file, HeifItem primary,
                                            ImageDecoderOptions options) {
        var limits = options.limits();
        HeifStill result = null;
        HeifGeometry full = null;
        if (options.minWidth() > 0 || options.minHeight() > 0) {
            try {
                full = geometry(source, file, primary, limits);
            } catch (MediaException.Io exception) {
                throw exception;
            } catch (MediaException exception) {
                full = null;
            }
        }
        if (full != null) {
            for (var thumbnail : thumbnails(source, file, primary, full, options)) {
                try {
                    result = StillDecoder.decode(source, file, thumbnail, limits, options.highBitDepth());
                    break;
                } catch (MediaException.Malformed | MediaException.Unsupported exception) {
                    result = null;
                }
            }
        }
        if (result == null) {
            result = decodeShown(source, file, primary, limits, options.highBitDepth());
            full = null;
        }
        var picture = result.picture();
        var sourceWidth = full == null ? picture.width() : full.width();
        var sourceHeight = full == null ? picture.height() : full.height();
        var orientation = full == null ? result.geometry().orientation() : full.orientation();
        var icc = icc(file, primary);
        var profile = HeifPictures.colorProfile(icc, picture);
        return new DecodedImage(picture, ImageFormat.HEIC, orientation, sourceWidth, sourceHeight,
                Optional.ofNullable(icc == null ? null : icc.clone()), Optional.ofNullable(exif(source, file, primary, limits)),
                Optional.ofNullable(file.xmpOf(source, primary, limits)), profile);
    }

    /**
     * Lists the thumbnails that may be decoded instead of the primary image under a reduced-size
     * hint, smallest first.
     *
     * <p>A thumbnail qualifies when its size after its clean aperture is at least the hint and
     * smaller than the primary image's, its orientation is the primary image's, and its aspect
     * ratio is the primary image's within two pixels of rounding.
     *
     * @param source the file
     * @param file the parsed file
     * @param primary the primary item
     * @param full the geometry of the primary item
     * @param options the decoding options, with a reduced-size hint
     * @return the qualifying thumbnail items, empty to decode the primary image
     */
    private static List<HeifItem> thumbnails(MediaSource source, HeifFile file, HeifItem primary, HeifGeometry full,
                                             ImageDecoderOptions options) {
        var found = new ArrayList<HeifItem>();
        var areas = new HashMap<HeifItem, Long>();
        for (var candidate : file.thumbnailsOf(primary.id())) {
            if (candidate.unsupportedEssential(StillDecoder.SUPPORTED_PROPERTIES) != null) {
                continue;
            }
            HeifGeometry geometry;
            try {
                geometry = geometry(source, file, candidate, options.limits());
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
     * Returns the first property of a kind associated with an image item or, when the item has
     * none, with the first input of each derived image down to a coded item.
     *
     * @param file the parsed file
     * @param item the image item
     * @param kind the property to look for
     * @param <T> the property type
     * @return the first such property found, or {@code null}
     */
    private static <T extends HeifProperty> T inherited(HeifFile file, HeifItem item, Class<T> kind) {
        var current = item;
        for (var depth = 0; current != null && depth <= MAX_LINEAGE; depth++) {
            var property = current.property(kind);
            if (property != null) {
                return property;
            }
            var inputs = current.references("dimg");
            current = inputs.length == 0 ? null : file.item(inputs[0]);
        }
        return null;
    }

    /**
     * Returns the ICC profile of an image item.
     *
     * @param file the parsed file
     * @param item the image item
     * @return the profile bytes of its {@code colr} property of type {@code prof} or {@code rICC},
     * or of the first input that has one, or {@code null}
     */
    private static byte[] icc(HeifFile file, HeifItem item) {
        var icc = inherited(file, item, HeifProperty.ColorIcc.class);
        return icc == null ? null : icc.profile();
    }

    /**
     * Reads the EXIF metadata that describes an image item, or the first input of a derived item
     * when the item itself has none.
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
        for (var depth = 0; current != null && depth <= MAX_LINEAGE; depth++) {
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
            current = inputs.length == 0 ? null : file.item(inputs[0]);
        }
        return null;
    }

    /**
     * Opens a HEIC file as an animation.
     *
     * @param source the file
     * @param limits the bounds
     * @param highBitDepth whether frames of a source deeper than 8 bits are 16-bit RGBA
     * @return the reader: the sequence when the file has an HEVC or AVC color track, otherwise the
     * shown still image as one frame shown for zero microseconds and played once; both can seek
     */
    public static AnimationReader openAnimation(MediaSource source, MediaLimits limits, boolean highBitDepth) {
        var file = HeifFile.parse(source, limits, false);
        if (file.hasMovie() && hasColorTrack(source, file, limits)) {
            return new SequenceReader(source, file, limits, highBitDepth);
        }
        var primary = primary(file);
        var info = stillInfo(source, file, shown(file, primary), limits);
        var frame = new Supplier<Picture>() {
            private Picture decoded;

            @Override
            public Picture get() {
                if (decoded == null) {
                    var picture = decodeShown(source, file, primary, limits, highBitDepth).picture();
                    decoded = picture instanceof Yuv16Picture || picture instanceof Rgba16Picture
                            ? ImageOps.toRgba16(picture) : ImageOps.toRgba(picture);
                }
                return decoded;
            }
        };
        return SeekableAnimationReader.of(() -> new StillReader(info.width(), info.height(), frame));
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
         * Creates the reader.
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
