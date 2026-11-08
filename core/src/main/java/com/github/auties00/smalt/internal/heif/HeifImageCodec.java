package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.AuxiliaryImage;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.PageReader;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.gainmap.GainMap;
import com.github.auties00.smalt.internal.provider.Artifacts;
import com.github.auties00.smalt.io.MediaSource;

import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Decodes the HEIF files and image items coded with the codecs of one Smalt artifact, so that each HEIF image
 * decodes with the artifact that implements its coding whatever the brands of its file: the HEIC decoder of
 * {@code smalt-mpeg} hands AV1-coded images to the AVIF decoder of {@code smalt-video}, which hands the images coded
 * with HEVC, AVC, JPEG, JPEG 2000 or as uncompressed samples back.
 *
 * <p>The family of an image is that of its coding: {@link #formatOf(String)} maps the type of a coded item to
 * {@link ImageFormat#AVIF} or {@link ImageFormat#HEIC}, a derived image (a grid, an identity derivation, an overlay
 * or a tone map) takes the family of its first input, and the sample transform ({@code sato}), which only AVIF
 * defines, is AVIF. A file belongs to the family of the image it shows ({@link #formatOf(MediaSource, HeifFile,
 * MediaLimits)}).
 *
 * <p>Implementations are found with {@link ServiceLoader}: a module provides one with {@code provides}, a jar on
 * the class path with a {@code META-INF/services} entry. They must have a public no-argument constructor that does
 * no work and must be thread-safe. The file-level operations decode a file the way the public decoder of the
 * artifact does, and the item-level ones decode one image item of a file already parsed.
 */
public interface HeifImageCodec {
    /**
     * The deepest chain of derived images followed to find the coding of an image.
     */
    int MAX_DERIVATION = 8;

    /**
     * {@return the family this codec decodes, {@link ImageFormat#HEIC} or {@link ImageFormat#AVIF}}
     */
    ImageFormat format();

    /**
     * Summarizes a file as its family's decoder does.
     *
     * @param source the file
     * @param limits the bounds
     * @return the summary
     * @throws MediaException.Malformed if the file is invalid
     * @throws MediaException.Unsupported if the file uses a feature the decoder lacks
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits
     */
    ImageInfo probe(MediaSource source, MediaLimits limits);

    /**
     * Decodes the image a file shows as its family's decoder does.
     *
     * @param source the file
     * @param options the decoding options
     * @return the decoded image
     * @throws MediaException.Malformed if the file is invalid
     * @throws MediaException.Unsupported if the file uses a feature the decoder lacks
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits
     */
    DecodedImage decode(MediaSource source, ImageDecoderOptions options);

    /**
     * Opens a file as an animation as its family's decoder does.
     *
     * @param source the file
     * @param options the decoding options
     * @return the reader
     * @throws MediaException.Malformed if the file is invalid
     * @throws MediaException.Unsupported if the file uses a feature the decoder lacks
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits
     */
    AnimationReader openAnimation(MediaSource source, ImageDecoderOptions options);

    /**
     * Opens the pages of a file as its family's decoder does.
     *
     * @param source the file
     * @param options the decoding options
     * @return the reader
     * @throws MediaException.Malformed if the file is invalid
     * @throws MediaException.Unsupported if the file uses a feature the decoder lacks
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits
     */
    PageReader openPages(MediaSource source, ImageDecoderOptions options);

    /**
     * Decodes the gain map of a file as its family's decoder does.
     *
     * @param source the file
     * @param options the decoding options
     * @return the gain map, or empty when the file has none
     * @throws MediaException.Malformed if the file is invalid
     * @throws MediaException.Unsupported if the gain map uses a feature the decoder lacks
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits
     */
    Optional<GainMap> decodeGainMap(MediaSource source, ImageDecoderOptions options);

    /**
     * Lists the auxiliary images of a file as its family's decoder does.
     *
     * @param source the file
     * @param limits the bounds
     * @return the images
     * @throws MediaException.Malformed if the file is invalid
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits
     */
    List<AuxiliaryImage> auxiliaryImages(MediaSource source, MediaLimits limits);

    /**
     * Decodes an auxiliary image of a file as its family's decoder does.
     *
     * @param source the file
     * @param index the index of the image in the list of {@link #auxiliaryImages}
     * @param options the decoding options
     * @return the image
     * @throws IndexOutOfBoundsException if the file lists no image of that index
     * @throws MediaException.Malformed if the file is invalid
     * @throws MediaException.Unsupported if the image uses a feature the decoder lacks
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits
     */
    DecodedImage decodeAuxiliaryImage(MediaSource source, int index, ImageDecoderOptions options);

    /**
     * Returns whether the decoder shows the image sequence of a file rather than its primary image.
     *
     * @param file the parsed file
     * @return {@code true} when {@link #decode(MediaSource, ImageDecoderOptions)} decodes the sequence
     */
    boolean showsSequence(HeifFile file);

    /**
     * Checks, without decoding pixels, that this codec reconstructs an image item.
     *
     * @param source the file
     * @param file the parsed file
     * @param item the image item
     * @param limits the bounds of the data read
     * @throws MediaException.Malformed if the item is inconsistent
     * @throws MediaException.Unsupported if the item uses a feature the decoder lacks
     */
    void requireDecodable(MediaSource source, HeifFile file, HeifItem item, MediaLimits limits);

    /**
     * Summarizes an image item, as {@link #probe(MediaSource, MediaLimits)} summarizes the image a file shows.
     *
     * @param source the file
     * @param file the parsed file
     * @param item the image item
     * @param limits the bounds
     * @return the summary, with a frame count of 1
     * @throws MediaException.Malformed if the item declares no valid size
     * @throws MediaException.Unsupported if the item cannot be reconstructed
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits
     */
    ImageInfo probe(MediaSource source, HeifFile file, HeifItem item, MediaLimits limits);

    /**
     * Decodes an image item as {@link #decode(MediaSource, ImageDecoderOptions)} decodes the primary item: the
     * alternative its alternatives group prefers when this codec reconstructs it, a thumbnail under a reduced-size
     * hint, with the item's own orientation, ICC profile, EXIF block and XMP packet, converted to sRGB when the
     * options ask.
     *
     * @param source the file
     * @param file the parsed file
     * @param item the image item
     * @param options the decoding options
     * @return the decoded image
     * @throws MediaException.Malformed if the item is corrupt
     * @throws MediaException.Unsupported if the item uses a feature the decoder lacks
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     */
    DecodedImage decode(MediaSource source, HeifFile file, HeifItem item, ImageDecoderOptions options);

    /**
     * Reconstructs a coded item of this family as the input of a derived image of the other family: its image
     * before its own transformative properties, with the alpha plane of its auxiliary image.
     *
     * <p>The reconstruction checks the item against the limits and its derived images against the nesting bound,
     * counting from {@code depth}, so that derivations that cross between the families cannot nest without end.
     *
     * @param source the file
     * @param file the parsed file
     * @param item the coded image item
     * @param limits the bounds of the item and its picture
     * @param highBitDepth whether a source of more than 8 bits keeps its depth
     * @param depth the number of derived images reconstructed on the way to the item
     * @return the picture: planar, or packed RGBA for an image premultiplied by its alpha
     * @throws MediaException.Malformed if the item is corrupt or nested too deep
     * @throws MediaException.Unsupported if the item uses a feature the decoder lacks
     * @throws MediaException.LimitExceeded if the picture exceeds the limits
     */
    Picture reconstructInput(MediaSource source, HeifFile file, HeifItem item, MediaLimits limits, boolean highBitDepth,
                             int depth);

    /**
     * Returns the family of a coded item type.
     *
     * @param type the item type
     * @return {@link ImageFormat#AVIF} for {@code av01} and {@code sato}, {@link ImageFormat#HEIC} for
     * {@code hvc1}, {@code lhv1}, {@code avc1}, {@code jpeg}, {@code j2k1} and {@code unci}, or {@code null} for a
     * derived type or a type no Smalt artifact decodes
     */
    static ImageFormat formatOf(String type) {
        return switch (type) {
            case "av01", "sato" -> ImageFormat.AVIF;
            case "hvc1", "lhv1", "avc1", "jpeg", "j2k1", "unci" -> ImageFormat.HEIC;
            default -> null;
        };
    }

    /**
     * Returns the family of an image item: that of its type, or for a grid, an identity derivation, an overlay or
     * a tone map, that of its first input, followed at most {@value #MAX_DERIVATION} deep.
     *
     * @param file the parsed file
     * @param item the image item
     * @return the family, or {@code null} when the item and its inputs are of no known coding
     */
    static ImageFormat formatOf(HeifFile file, HeifItem item) {
        var current = item;
        for (var depth = 0; current != null && depth <= MAX_DERIVATION; depth++) {
            var format = formatOf(current.type());
            if (format != null) {
                return format;
            }
            switch (current.type()) {
                case "grid", "iden", "iovl", "tmap" -> {
                    var inputs = current.references("dimg");
                    current = inputs.length == 0 ? null : file.item(inputs[0]);
                }
                default -> current = null;
            }
        }
        return null;
    }

    /**
     * Returns the family of the image a file shows: that of its primary item, or of the color track of its image
     * sequence when it has no primary item, or for a file whose major brand is {@code avis}, AVIF.
     *
     * @param source the file
     * @param file the parsed file
     * @param limits the bounds of the sequence structure
     * @return the family, or {@code null} when it is unknown
     * @throws MediaException.Io if reading the source fails
     */
    static ImageFormat formatOf(MediaSource source, HeifFile file, MediaLimits limits) {
        if (file.majorBrand().equals("avis")) {
            return ImageFormat.AVIF;
        }
        var primary = file.primaryItem();
        if (primary != null) {
            return formatOf(file, primary);
        }
        if (!file.hasMovie()) {
            return null;
        }
        try {
            var color = HeifSequence.colorTrack(HeifMovie.parse(source, file, limits), _ -> true, true);
            return color == null ? null : switch (color.sampleEntry()) {
                case "av01" -> ImageFormat.AVIF;
                case "hvc1", "hev1", "avc1", "avc3" -> ImageFormat.HEIC;
                default -> null;
            };
        } catch (MediaException.Malformed | MediaException.Unsupported | MediaException.LimitExceeded exception) {
            return null;
        }
    }

    /**
     * Returns the alpha auxiliary image of an item that is coded in the other family, when the codec of that family
     * is at hand and reconstructs it.
     *
     * <p>A candidate is an auxiliary item of {@code item} ({@code auxl} reference) whose {@code auxC} property names
     * an alpha URN, whose type is coded in a family other than {@code own}, which has data and which the codec of
     * its family accepts ({@link #requireDecodable}); an alpha image no codec at hand reconstructs is ignored.
     *
     * @param source the file
     * @param file the parsed file
     * @param item the item whose alpha is wanted
     * @param limits the bounds of the data read
     * @param own the family of the decoder asking, whose own alpha images it finds itself
     * @return the first such candidate in item order, or {@code null}
     * @throws MediaException.Io if reading the source fails
     */
    static HeifItem foreignAlpha(MediaSource source, HeifFile file, HeifItem item, MediaLimits limits,
                                 ImageFormat own) {
        for (var candidate : file.auxiliariesOf(item.id())) {
            var type = candidate.property(HeifProperty.AuxiliaryType.class);
            var format = formatOf(candidate.type());
            if (type == null || !type.isAlpha() || format == null || format == own || !candidate.isLocated()
                    || candidate.size() == 0) {
                continue;
            }
            var codec = find(format);
            if (codec == null) {
                continue;
            }
            try {
                codec.requireDecodable(source, file, candidate, limits);
                return candidate;
            } catch (MediaException.Malformed | MediaException.Unsupported | MediaException.LimitExceeded exception) {
                // a reader ignores an alpha image it cannot reconstruct
            }
        }
        return null;
    }

    /**
     * Returns the codec of a family.
     *
     * @param format {@link ImageFormat#HEIC} or {@link ImageFormat#AVIF}
     * @return the first codec found for it, or {@code null} when no artifact on the module or class path provides
     * one
     */
    static HeifImageCodec find(ImageFormat format) {
        for (var codec : Holder.CODECS) {
            if (codec.format() == format) {
                return codec;
            }
        }
        return null;
    }

    /**
     * Returns the codec of a family, which must be present.
     *
     * @param format {@link ImageFormat#HEIC} or {@link ImageFormat#AVIF}
     * @return the codec
     * @throws MediaException.Unsupported naming the artifact to add when no artifact provides it
     */
    static HeifImageCodec require(ImageFormat format) {
        var codec = find(format);
        if (codec == null) {
            throw Artifacts.unsupported(Artifacts.nameOf(format), "decodable", Artifacts.imageDecoderOf(format));
        }
        return codec;
    }

    /**
     * Holds the codecs, looked up once.
     */
    final class Holder {
        /**
         * The codecs found.
         */
        private static final List<HeifImageCodec> CODECS = ServiceLoader.load(HeifImageCodec.class,
                HeifImageCodec.class.getClassLoader()).stream().map(ServiceLoader.Provider::get).toList();

        /**
         * Prevents instantiation.
         */
        private Holder() {
            throw new AssertionError();
        }
    }
}
