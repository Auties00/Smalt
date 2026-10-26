package com.github.auties00.smalt.image.tiff;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.PageReader;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ColorTransform;
import com.github.auties00.smalt.image.color.ProfileColorSpace;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.Region;
import com.github.auties00.smalt.image.tiff.internal.TiffDirectory;
import com.github.auties00.smalt.image.tiff.internal.TiffExif;
import com.github.auties00.smalt.image.tiff.internal.TiffFile;
import com.github.auties00.smalt.image.tiff.internal.TiffPageDecoder;
import com.github.auties00.smalt.image.tiff.internal.TiffTags;
import com.github.auties00.smalt.image.tiff.internal.TransparencyMask;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.region.ImageRegions;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads the pages of a TIFF file: the image file directories of its main chain, in any order.
 *
 * <p>Opening reads only the directories; each {@link #read(int)} decodes one page with the options
 * the reader was opened with. Sub-directories ({@code SubIFDs}, such as the reduced-resolution
 * levels of a pyramid or the EXIF directory) are not pages; a page's EXIF and GPS directories
 * reach its {@link DecodedImage#exif()}, its {@code XMLPacket} tag its {@link DecodedImage#xmp()},
 * its resolution fields its {@link DecodedImage#density()}, as {@link TiffPage#density()} reads them, and
 * its reduced-resolution levels serve the size hint of {@link ImageDecoderOptions}.
 *
 * <p>The source is not closed by {@link #close()}. Instances are not thread-safe.
 */
public final class TiffPages implements PageReader {
    /**
     * The largest number of sub-directories followed from one page when looking for
     * reduced-resolution levels.
     */
    private static final int MAX_LEVELS = 64;

    /**
     * The {@code NewSubfileType} bit marking a reduced-resolution image.
     */
    private static final long REDUCED_RESOLUTION = 1;

    /**
     * The {@code NewSubfileType} bit marking a transparency mask.
     */
    private static final long TRANSPARENCY_MASK = 4;

    /**
     * The photometric interpretation of a transparency mask.
     */
    private static final long MASK_PHOTOMETRIC = 4;

    /**
     * The photometric interpretation of a separated image.
     */
    private static final long SEPARATED_PHOTOMETRIC = 5;

    /**
     * The file structure.
     */
    private final TiffFile file;

    /**
     * The decoding options.
     */
    private final ImageDecoderOptions options;

    /**
     * The page descriptions, read on demand.
     */
    private final TiffPage[] pages;

    /**
     * Constructs a reader over a parsed file.
     *
     * @param file the file structure
     * @param options the decoding options
     */
    TiffPages(TiffFile file, ImageDecoderOptions options) {
        this.file = file;
        this.options = options;
        this.pages = new TiffPage[file.pages().size()];
    }

    @Override
    public int pageCount() {
        return pages.length;
    }

    /**
     * Describes a page from its directory without decoding pixels.
     *
     * @param index the page index, from zero to {@link #pageCount()} minus one
     * @return the description
     * @throws IndexOutOfBoundsException if {@code index} is out of range
     * @throws MediaException.Malformed if the directory has a tag of an invalid type
     * @throws MediaException.LimitExceeded if a tag's values exceed the byte limit, or reading
     * them runs out of memory
     * @throws MediaException.Io if reading the source fails
     */
    public TiffPage page(int index) {
        Objects.checkIndex(index, pages.length);
        if (pages[index] == null) {
            pages[index] = Guards.malformed("TIFF file", () -> TiffPage.of(index, file.pages().get(index)));
        }
        return pages[index];
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@link ImageInfo#hasAlpha()} follows {@link TiffPage#hasAlpha()}, and is also {@code true}
     * when a transparency mask the decoder supports applies to the page (see {@link #read(int)});
     * {@link ImageInfo#animated()} is always {@code false}: TIFF pages are not frames.
     */
    @Override
    public ImageInfo probe(int index) {
        var page = page(index);
        if (page.width() <= 0 || page.height() <= 0) {
            throw new MediaException.Malformed("the TIFF page has no valid size");
        }
        var alpha = page.hasAlpha() || hasMask(index);
        return new ImageInfo(ImageFormat.TIFF, page.width(), page.height(), alpha, false, pages.length,
                page.orientation());
    }

    /**
     * {@inheritDoc}
     *
     * <p>The picture is an {@link RgbaPicture}, or an
     * {@link com.github.auties00.smalt.image.Rgba16Picture} for samples wider than 8 bits when
     * {@link ImageDecoderOptions#highBitDepth()} is set; {@link TiffDecoder} describes the sample
     * conversions. The orientation is reported, not applied.
     *
     * <p>When the options ask for a minimum size and the page has reduced-resolution levels, the
     * smallest level still at least {@link ImageDecoderOptions#minWidth()} wide and
     * {@link ImageDecoderOptions#minHeight()} tall is decoded instead of the page, and
     * {@link DecodedImage#sourceWidth()} and {@link DecodedImage#sourceHeight()} report the page's
     * size. A level is a directory marked reduced-resolution ({@code NewSubfileType} bit 0, and
     * not a transparency mask) that is smaller than the page with the same aspect ratio, to within
     * a pixel: one listed in the page's {@code SubIFDs} (or chained after one), or a main-chain
     * directory that follows the page with only reduced-resolution directories between them. A
     * level that cannot be decoded is passed over for the next larger one, ending at the page
     * itself. The color profile, EXIF block and orientation are always the page's.
     *
     * <p>A CMYK page is converted naively and reports an empty {@link DecodedImage#colorProfile()},
     * since its embedded CMYK profile does not describe those pixels; with
     * {@link ImageDecoderOptions#convertToSrgb()} set and an embedded CMYK profile, a CMYK page
     * without alpha is converted to sRGB through that profile instead. L*a*b*, LogL and LogLuv
     * pages are rendered for display (see {@link TiffDecoder}) and also report an empty color
     * profile. A separated page of another ink set ({@code InkSet} 2) is converted to sRGB through its
     * embedded ICC profile, which must have one channel per ink; the result reports the sRGB profile and
     * no embedded profile. Every other page reports the profile its ICC tag declares and, with
     * {@link ImageDecoderOptions#convertToSrgb()} set, is converted as
     * {@link ImageOps#toSrgb(DecodedImage)} converts it. With {@link ImageDecoderOptions#keepChannels()} set
     * (and {@link ImageDecoderOptions#convertToSrgb()} not set), the pages {@link TiffDecoder} lists decode to
     * channel pictures instead.
     *
     * <p>A transparency mask (photometric interpretation 4) applies to the page it masks: the nearest
     * page before it in the main chain that is neither a mask nor reduced-resolution, or the page whose
     * {@code SubIFDs} list it. The mask covers the whole picture whatever its own size; each pixel's alpha
     * is multiplied by the mean of the mask samples over its area (1-bit samples: 0 outside the masked
     * region, 1 inside), or by the mask sample under its center when the mask is smaller, and a picture
     * without alpha gains it. A mask that cannot be decoded is ignored. A mask page read by itself
     * decodes as a black-is-zero gray picture.
     *
     * @param index the page index, from zero to {@link #pageCount()} minus one
     * @return the decoded page
     * @throws IndexOutOfBoundsException    if {@code index} is out of range
     * @throws MediaException.Unsupported   if the page uses a feature the decoder lacks, or if a separated
     *                                      page of an ink set other than CMYK has no embedded ICC profile
     *                                      with one channel per ink that converts to sRGB
     * @throws MediaException.Malformed     if the page is corrupt
     * @throws MediaException.LimitExceeded if the page exceeds the limits
     * @throws MediaException.Io            if reading the source fails
     */
    @Override
    public DecodedImage read(int index) {
        Objects.checkIndex(index, pages.length);
        return Guards.malformed("TIFF file", () -> decode(index));
    }

    /**
     * Decodes a page or, when the options ask for a minimum size, its smallest level that is
     * large enough.
     *
     * @param index the page index
     * @return the decoded image
     */
    private DecodedImage decode(int index) {
        try (var _ = ParallelBands.operation()) {
            var directory = file.pages().get(index);
            var full = pageDecoder(directory);
            var region = options.region().orElse(null);
            if (region != null) {
                ImageRegions.check(region, full.width(), full.height());
            }
            var profile = directory.bytes(TiffTags.ICC_PROFILE);
            var icc = Optional.ofNullable(profile != null && profile.length > 0 ? profile : null);
            var exif = Optional.ofNullable(TiffExif.extract(directory));
            var xmp = Optional.ofNullable(xmp(directory));
            var density = Optional.ofNullable(TiffPage.density(directory));
            if (ImageRegions.hinted(options)) {
                var hint = region == null ? options
                        : ImageRegions.scaledHint(options, region, full.width(), full.height());
                for (var level : levels(index, full.width(), full.height())) {
                    if (level.width() < hint.minWidth() || level.height() < hint.minHeight()) {
                        continue;
                    }
                    try {
                        var area = area(region, full, level.width(), level.height());
                        return masked(index, image(level.directory(), pageDecoder(level.directory()), full, icc,
                                exif, area).withXmp(xmp), area, level.width(), level.height()).withDensity(density);
                    } catch (MediaException.Unsupported | MediaException.Malformed ignored) {
                        // an undecodable level gives way to the next larger one, then to the page
                    }
                }
            }
            var area = area(region, full, full.width(), full.height());
            return masked(index, image(directory, full, full, icc, exif, area).withXmp(xmp), area, full.width(),
                    full.height()).withDensity(density);
        }
    }

    /**
     * Returns the rectangle of a page or of one of its levels that a decode covers.
     *
     * @param region the region of the options, or {@code null} for the whole page
     * @param page the decoder of the page
     * @param width the width of the page or level decoded
     * @param height the height of the page or level decoded
     * @return the rectangle, in the pixels of the page or level decoded
     */
    private static Region area(Region region, TiffPageDecoder page, int width, int height) {
        if (region == null) {
            return new Region(0, 0, width, height);
        }
        return ImageRegions.map(region, page.width(), page.height(), width, height);
    }

    /**
     * Applies the transparency mask of a page to a decoded rectangle of the page or of one of its levels.
     *
     * @param index the page index
     * @param image the decoded rectangle
     * @param area the rectangle, in the pixels of the page or level decoded
     * @param width the width of the page or level decoded
     * @param height the height of the page or level decoded
     * @return the masked image, or {@code image} itself when no decodable mask applies
     */
    private DecodedImage masked(int index, DecodedImage image, Region area, int width, int height) {
        var mask = maskOf(index);
        if (mask == null) {
            return image;
        }
        TiffPageDecoder decoder;
        RgbaPicture maskPicture;
        int[] covered;
        try {
            decoder = TiffPageDecoder.of(mask);
            covered = TransparencyMask.coverage(area.x(), area.y(), area.width(), area.height(), width, height,
                    decoder.width(), decoder.height());
            maskPicture = (RgbaPicture) decoder.decode(false, covered[0], covered[1], covered[2], covered[3]);
        } catch (MediaException.Unsupported | MediaException.Malformed exception) {
            return image;
        }
        var picture = TransparencyMask.apply(image.picture(), area.x(), area.y(), width, height, maskPicture,
                covered[0], covered[1], decoder.width(), decoder.height());
        return new DecodedImage(picture, image.format(), image.orientation(), image.sourceWidth(),
                image.sourceHeight(), image.iccProfile(), image.exif(), image.xmp(), image.colorProfile(),
                image.density());
    }

    /**
     * Returns whether a transparency mask the decoder supports applies to a page, without decoding
     * its samples.
     *
     * @param index the page index
     * @return {@code true} when the page has a mask whose layout decodes
     */
    private boolean hasMask(int index) {
        var mask = maskOf(index);
        if (mask == null) {
            return false;
        }
        try {
            TiffPageDecoder.of(mask);
            return true;
        } catch (MediaException.Unsupported | MediaException.Malformed ignored) {
            return false;
        } catch (MediaException.LimitExceeded ignored) {
            return true;
        }
    }

    /**
     * Finds the transparency mask of a page.
     *
     * @param index the page index
     * @return the directory of the mask, or {@code null} when the page is a mask, is reduced-resolution
     * or has none
     */
    private TiffDirectory maskOf(int index) {
        var directory = file.pages().get(index);
        if (photometric(directory) == MASK_PHOTOMETRIC || (subfileType(directory) & REDUCED_RESOLUTION) != 0) {
            return null;
        }
        long[] subDirectories;
        try {
            subDirectories = directory.longs(TiffTags.SUB_IFDS);
        } catch (MediaException.Malformed ignored) {
            subDirectories = null;
        }
        if (subDirectories != null) {
            for (var offset : subDirectories) {
                try {
                    var sub = file.readDirectory(offset);
                    if (photometric(sub) == MASK_PHOTOMETRIC && (subfileType(sub) & REDUCED_RESOLUTION) == 0) {
                        return sub;
                    }
                } catch (MediaException.Malformed ignored) {
                    // a damaged sub-directory is not a mask
                }
            }
        }
        for (var i = index + 1; i < pages.length; i++) {
            var page = file.pages().get(i);
            var reduced = (subfileType(page) & REDUCED_RESOLUTION) != 0;
            if (photometric(page) == MASK_PHOTOMETRIC && !reduced) {
                return page;
            }
            if (!reduced) {
                return null;
            }
        }
        return null;
    }

    /**
     * Returns the photometric interpretation a directory stores.
     *
     * @param directory the directory whose tag is read
     * @return the value, {@code -1} when absent or unreadable
     */
    private static long photometric(TiffDirectory directory) {
        try {
            return directory.longValue(TiffTags.PHOTOMETRIC, -1);
        } catch (MediaException.Malformed ignored) {
            return -1;
        }
    }

    /**
     * Returns whether a directory is a separated image of an ink set other than CMYK.
     *
     * @param directory the directory whose tags are read
     * @return {@code true} for {@code InkSet} 2 (or any value other than 1)
     */
    private static boolean namedInks(TiffDirectory directory) {
        try {
            return photometric(directory) == SEPARATED_PHOTOMETRIC && directory.longValue(TiffTags.INK_SET, 1) != 1;
        } catch (MediaException.Malformed ignored) {
            return false;
        }
    }

    /**
     * Reads the XMP packet of a page, its {@code XMLPacket} tag (700).
     *
     * <p>Metadata is not needed to show the page, so a tag whose values lie outside the file or
     * exceed the limits is left out.
     *
     * @param directory the page directory
     * @return the packet bytes, or {@code null} when the page has none
     */
    private static byte[] xmp(TiffDirectory directory) {
        try {
            var packet = directory.bytes(TiffTags.XMP);
            return packet != null && packet.length > 0 ? packet : null;
        } catch (MediaException.Malformed | MediaException.LimitExceeded exception) {
            return null;
        }
    }

    /**
     * Decodes a page or one of its levels into the result.
     *
     * @param directory the directory decoded
     * @param decoder the decoder of {@code directory}
     * @param page the decoder of the page, which supplies the source size and orientation
     * @param icc the page's ICC profile, or empty
     * @param exif the page's EXIF block, or empty
     * @param area the rectangle of {@code directory} to decode
     * @return the decoded image
     */
    private DecodedImage image(TiffDirectory directory, TiffPageDecoder decoder, TiffPageDecoder page,
                               Optional<byte[]> icc, Optional<byte[]> exif, Region area) {
        var orientation = page.orientation();
        var left = area.x();
        var top = area.y();
        var right = area.x() + area.width();
        var bottom = area.y() + area.height();
        if (decoder.channels()) {
            var picture = (ChannelPicture) decoder.decode(options.highBitDepth() || !keepsChannels(), left, top, right,
                    bottom);
            var parsed = new DecodedImage(picture, ImageFormat.TIFF, orientation, page.width(), page.height(), icc,
                    exif);
            var colors = picture.layout().colorChannels();
            var image = new DecodedImage(picture, ImageFormat.TIFF, orientation, page.width(), page.height(), icc,
                    exif, parsed.colorProfile().filter(profile -> profile.channels() == colors));
            return keepsChannels() ? image : inksToSrgb(image, colors, options.highBitDepth());
        }
        switch (TiffPhotometric.of(decoder.photometric())) {
            case SEPARATED -> {
                if (options.convertToSrgb()) {
                    var converted = cmykToSrgb(directory, icc, area);
                    if (converted != null) {
                        return new DecodedImage(converted, ImageFormat.TIFF, orientation, page.width(), page.height(),
                                Optional.empty(), exif, Optional.of(ColorProfile.srgb()));
                    }
                }
                return new DecodedImage(decoder.decode(options.highBitDepth(), left, top, right, bottom),
                        ImageFormat.TIFF, orientation, page.width(), page.height(), icc, exif, Optional.empty());
            }
            case CIELAB, ICCLAB, ITULAB, LOG_L, LOG_LUV -> {
                return new DecodedImage(decoder.decode(options.highBitDepth(), left, top, right, bottom),
                        ImageFormat.TIFF, orientation, page.width(), page.height(), icc, exif, Optional.empty());
            }
            default -> {
                var image = new DecodedImage(decoder.decode(options.highBitDepth(), left, top, right, bottom),
                        ImageFormat.TIFF, orientation, page.width(), page.height(), icc, exif);
                return options.convertToSrgb() ? ImageOps.toSrgb(image) : image;
            }
        }
    }

    /**
     * Validates a directory for decoding as the options ask: to a channel picture when they keep channels and
     * the page needs one.
     *
     * @param directory the image file directory to decode
     * @return the decoder
     */
    private TiffPageDecoder pageDecoder(TiffDirectory directory) {
        return keepsChannels() || namedInks(directory) ? TiffPageDecoder.ofChannels(directory)
                : TiffPageDecoder.of(directory);
    }

    /**
     * Returns whether the options keep channel pictures.
     *
     * @return {@code true} when {@link ImageDecoderOptions#keepChannels()} is set and
     * {@link ImageDecoderOptions#convertToSrgb()} is not
     */
    private boolean keepsChannels() {
        return options.keepChannels() && !options.convertToSrgb();
    }

    /**
     * Converts a decoded separation of named inks to sRGB through its embedded profile.
     *
     * @param image the decoded channel picture, with the embedded profile as its color profile when that
     * profile has one channel per ink
     * @param inks the number of inks
     * @param highBitDepth whether a picture of samples wider than 8 bits stays 16-bit
     * @return the image holding packed RGBA, converted at the depth of the samples and then reduced to 8 bits
     * unless {@code highBitDepth} is set
     * @throws MediaException.Unsupported if the page has no such profile, or the profile does not convert
     * to sRGB
     */
    private static DecodedImage inksToSrgb(DecodedImage image, int inks, boolean highBitDepth) {
        if (image.colorProfile().isEmpty()) {
            throw new MediaException.Unsupported("tiff ink set", "the TIFF separated image of " + inks
                    + " inks has no embedded ICC profile with " + inks + " channels to convert it through");
        }
        DecodedImage converted;
        try {
            converted = ImageOps.toSrgb(image);
        } catch (MediaException.Unsupported exception) {
            throw new MediaException.Unsupported("tiff ink set", "the embedded ICC profile of the TIFF separated"
                    + " image of " + inks + " inks does not convert to sRGB: " + exception.getMessage());
        }
        if (converted.picture() instanceof ChannelPicture) {
            throw new MediaException.Unsupported("tiff ink set", "the embedded ICC profile of the TIFF separated"
                    + " image of " + inks + " inks does not convert to sRGB");
        }
        if (!highBitDepth && converted.picture() instanceof Rgba16Picture wide) {
            return new DecodedImage(ImageOps.toEightBit(wide), converted.format(), converted.orientation(),
                    converted.sourceWidth(), converted.sourceHeight(), converted.iccProfile(), converted.exif(),
                    converted.xmp(), converted.colorProfile(), converted.density());
        }
        return converted;
    }

    /**
     * Converts a CMYK directory to sRGB through its embedded CMYK profile.
     *
     * @param directory the CMYK directory
     * @param icc the embedded ICC profile, or empty
     * @param area the rectangle of the directory to convert
     * @return the opaque sRGB picture of the rectangle, or {@code null} when there is no usable CMYK profile or
     * the image has alpha
     */
    private static RgbaPicture cmykToSrgb(TiffDirectory directory, Optional<byte[]> icc, Region area) {
        if (icc.isEmpty()) {
            return null;
        }
        ColorTransform transform;
        try {
            var profile = ColorProfile.parse(icc.get());
            if (profile.colorSpace() != ProfileColorSpace.CMYK) {
                return null;
            }
            transform = ColorTransform.create(profile, ColorProfile.srgb());
        } catch (MediaException.Malformed | MediaException.Unsupported exception) {
            return null;
        }
        var decoder = TiffPageDecoder.ofRawCmyk(directory);
        if (decoder.hasAlpha()) {
            return null;
        }
        var raw = (RgbaPicture) decoder.decode(false, area.x(), area.y(), area.x() + area.width(),
                area.y() + area.height());
        var samples = raw.pixels();
        var width = raw.width();
        var height = raw.height();
        var bands = ParallelBands.bands(height, 32L * width * height);
        ParallelBands.run(bands, band -> {
            var top = ParallelBands.start(band, bands, height);
            var bottom = ParallelBands.start(band + 1, bands, height);
            var start = top * width * 4;
            var end = bottom * width * 4;
            transform.transform(samples, start, 4, samples, start, 4, (bottom - top) * width);
            for (var i = start + 3; i < end; i += 4) {
                samples[i] = (byte) 0xFF;
            }
        });
        return RgbaPicture.of(width, height, width * 4, samples, false);
    }

    /**
     * Lists the reduced-resolution levels of a page, smallest first.
     *
     * @param index the page index
     * @param width the page width
     * @param height the page height
     * @return the levels; damaged sub-directories are skipped
     */
    private List<Level> levels(int index, int width, int height) {
        var candidates = new ArrayList<TiffDirectory>();
        var directory = file.pages().get(index);
        long[] subDirectories;
        try {
            subDirectories = directory.longs(TiffTags.SUB_IFDS);
        } catch (MediaException.Malformed ignored) {
            subDirectories = null;
        }
        if (subDirectories != null) {
            var seen = new HashSet<Long>();
            for (var offset : subDirectories) {
                var next = offset;
                while (next != 0 && seen.size() < MAX_LEVELS && seen.add(next)) {
                    try {
                        var sub = file.readDirectory(next);
                        candidates.add(sub);
                        next = sub.next();
                    } catch (MediaException.Malformed ignored) {
                        break;
                    }
                }
            }
        }
        for (var i = index + 1; i < pages.length; i++) {
            var page = file.pages().get(i);
            if ((subfileType(page) & REDUCED_RESOLUTION) == 0) {
                break;
            }
            candidates.add(page);
        }
        var levels = new ArrayList<Level>();
        for (var candidate : candidates) {
            var type = subfileType(candidate);
            if ((type & REDUCED_RESOLUTION) == 0 || (type & TRANSPARENCY_MASK) != 0) {
                continue;
            }
            long levelWidth;
            long levelHeight;
            try {
                levelWidth = candidate.longValue(TiffTags.IMAGE_WIDTH, 0);
                levelHeight = candidate.longValue(TiffTags.IMAGE_LENGTH, 0);
            } catch (MediaException.Malformed ignored) {
                continue;
            }
            if (levelWidth <= 0 || levelHeight <= 0 || levelWidth > width || levelHeight > height
                    || (levelWidth == width && levelHeight == height)) {
                continue;
            }
            if (Math.abs(levelWidth * height - levelHeight * width) > (long) width + height) {
                continue;
            }
            levels.add(new Level(candidate, (int) levelWidth, (int) levelHeight));
        }
        levels.sort(Comparator.comparingLong(level -> (long) level.width() * level.height()));
        return levels;
    }

    /**
     * Returns the {@code NewSubfileType} of a directory.
     *
     * @param directory the directory whose type is read
     * @return the value, {@code 0} when absent or unreadable
     */
    private static long subfileType(TiffDirectory directory) {
        try {
            return directory.longValue(TiffTags.NEW_SUBFILE_TYPE, 0);
        } catch (MediaException.Malformed ignored) {
            return 0;
        }
    }

    /**
     * Describes one reduced-resolution level of a page.
     *
     * @param directory the level's directory
     * @param width the level's width
     * @param height the level's height
     */
    private record Level(TiffDirectory directory, int width, int height) {
    }
}
