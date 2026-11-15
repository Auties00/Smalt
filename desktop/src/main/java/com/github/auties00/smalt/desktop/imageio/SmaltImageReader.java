package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.AnimationFrame;
import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.AuxiliaryImageType;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.PageReader;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.heif.HeifImageCodec;
import com.github.auties00.smalt.io.MediaSource;

import javax.imageio.IIOException;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads images through the Smalt codec registry ({@link Images}) for ImageIO.
 *
 * <p>A still image is image {@code 0}. An animated image (animated WebP, APNG) is a sequence of
 * images, one per frame, each the fully composited canvas as the animation shows it; the frame
 * timing and loop count are in each frame's {@link SmaltImageMetadata}. Frames are decoded in
 * order, so reading frame {@code i} after frame {@code j > i} decodes the animation again from its
 * start. The pages of a multi-page image (a TIFF, an icon) are images too, one per page, each
 * decoded on its own when it is read.
 *
 * <p>An image of more than 8 bits per sample (a 16-bit PNG, TIFF or JPEG XL, a 10 or 12-bit AVIF or
 * HEIC) reads as 16-bit samples by default: as {@link BufferedImage#TYPE_USHORT_GRAY} when it is gray
 * and opaque, and otherwise as an interleaved {@link DataBuffer#TYPE_USHORT} image of gray and alpha, RGB
 * or RGBA samples in the sRGB or linear gray color space, the layout the JDK's PNG reader gives a 16-bit
 * PNG. A deep picture is gray when it has no chroma or its three color samples are equal in every pixel.
 * Other images read as {@link BufferedImage#TYPE_INT_ARGB} when they have alpha,
 * {@link BufferedImage#TYPE_BYTE_GRAY} when they are monochrome and {@link BufferedImage#TYPE_INT_RGB}
 * otherwise. The 8-bit types are offered for every image: a deep image read into one is reduced to 8 bits
 * as its decoder reduces it by default ({@link com.github.auties00.smalt.image.ops.ImageOps#toEightBit}).
 * An {@link ImageReadParam} may name another destination type or image. The EXIF orientation is reported in
 * the metadata and never applied, as the JDK's plug-ins do.
 *
 * <p>{@link ImageReadParam} source regions, subsampling (with offsets), destination offsets,
 * destination images, destination types and source and destination bands are honored. The bands of an
 * image are those of the first type {@link #getImageTypes(int)} returns: gray, gray and alpha, red green
 * and blue, or red green blue and alpha; {@code TYPE_INT_ARGB} and {@code TYPE_INT_RGB} have them in that
 * order. A selected band written to a destination band of another sample size is rescaled as
 * {@code round(v * (2^t - 1) / (2^s - 1))}. {@link #readRaster(int, ImageReadParam)} returns the samples of
 * those bands, in the sample model of the first type, with the selected source bands only.
 *
 * <p>When the read subsamples a still image, the decoder is asked for a picture of at least the subsampled
 * size ({@link ImageDecoderOptions.Builder#minSize(int, int)}), which lets decoders that shrink while
 * decoding (JPEG, for example) skip the full-size picture. When a read without subsampling names a
 * source region smaller than a still image that was not decoded whole before, only that region is decoded
 * ({@link ImageDecoderOptions.Builder#region(int, int, int, int)}), which the decoders of tiled and
 * block-coded formats do without decoding the rest of the image; the pixels are those of the whole decode.
 *
 * <p>Thumbnails are the thumbnail items of an AVIF or HEIC still image, then the thumbnail of the EXIF
 * block of a still image or page ({@code IFD1}, a JPEG or uncompressed RGB thumbnail), in that order; they
 * read as 8-bit images of the types above.
 *
 * <p>Instances are not thread-safe.
 *
 * @implNote This implementation samples a reduced-size picture at the positions the subsampling
 * selects, scaled to the reduced size, so a subsampled read of a format that shrinks while decoding
 * shows the decoder's filtered pixels rather than every n-th source pixel; formats that decode at
 * full size give exactly the pixels ImageIO's point subsampling defines.
 */
public final class SmaltImageReader extends ImageReader {
    /**
     * The decoding options of still images and pages: full size, keeping more than 8 bits per sample.
     */
    private static final ImageDecoderOptions DEEP = ImageDecoderOptions.builder().highBitDepth(true).build();

    /**
     * The source over the input, created on first use.
     */
    private MediaSource source;

    /**
     * The image summary, read on first use.
     */
    private ImageInfo info;

    /**
     * The full-size decode of a still image, kept for later reads of the same input.
     */
    private DecodedImage still;

    /**
     * The open animation, or {@code null}.
     */
    private AnimationReader animation;

    /**
     * The index of the frame {@link #current} holds, or {@code -1}.
     */
    private int currentIndex = -1;

    /**
     * The last frame read from {@link #animation}, valid until the next read.
     */
    private AnimationFrame current;

    /**
     * The timing of each frame read so far, as {@code {timestampMicros, durationMicros}}.
     */
    private final List<long[]> timings = new ArrayList<>();

    /**
     * The number of frames, once known by reading to the end, or {@code -1}.
     */
    private int frameCount = -1;

    /**
     * The open pages of a multi-page image, or {@code null}.
     */
    private PageReader pages;

    /**
     * The index of the page {@link #page} holds, or {@code -1}.
     */
    private int pageIndex = -1;

    /**
     * The last page decoded, kept for later reads of the same page.
     */
    private DecodedImage page;

    /**
     * The thumbnails of each image index listed so far.
     */
    private final Map<Integer, List<ImageThumbnail>> thumbnails = new HashMap<>();

    /**
     * The deep picture last converted to 16-bit RGBA, or {@code null}.
     */
    private Picture widened;

    /**
     * The 16-bit RGBA form of {@link #widened}.
     */
    private Rgba16Picture widenedRgba;

    /**
     * Whether {@link #widened} is gray.
     */
    private boolean widenedGray;

    /**
     * Constructs a reader.
     *
     * @param provider the provider that created it
     */
    SmaltImageReader(ImageReaderSpi provider) {
        super(provider);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The input must be an {@link ImageInputStream}, positioned at the first byte of the image.
     */
    @Override
    public void setInput(Object input, boolean seekForwardOnly, boolean ignoreMetadata) {
        if (input != null && !(input instanceof ImageInputStream)) {
            throw new IllegalArgumentException("the input must be an ImageInputStream, not " + input.getClass().getName());
        }
        super.setInput(input, seekForwardOnly, ignoreMetadata);
        clear();
    }

    @Override
    public int getNumImages(boolean allowSearch) throws IOException {
        var summary = info();
        if (paged()) {
            return summary.frameCount();
        }
        if (!summary.animated()) {
            return 1;
        }
        if (summary.frameCount() >= 0) {
            return summary.frameCount();
        }
        if (frameCount >= 0) {
            return frameCount;
        }
        if (!allowSearch) {
            return -1;
        }
        if (seekForwardOnly) {
            throw new IllegalStateException("counting the frames needs a seekable input");
        }
        while (frameCount < 0) {
            frame(timings.size());
        }
        return frameCount;
    }

    @Override
    public int getWidth(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return paged() ? pageInfo(imageIndex).width() : info().width();
    }

    @Override
    public int getHeight(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return paged() ? pageInfo(imageIndex).height() : info().height();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The first type is the one a read without a destination produces. For a still image the
     * types depend on the decoded picture, so this method decodes the image at full size; the
     * decode is kept for the next {@link #read(int, ImageReadParam)}.
     */
    @Override
    public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        if (info().animated()) {
            return animatedTypes().iterator();
        }
        if (paged()) {
            return typesFor(page(imageIndex).picture()).iterator();
        }
        return typesFor(still().picture()).iterator();
    }

    /**
     * Returns {@code null}: the readers have no stream metadata.
     *
     * @return {@code null}
     */
    @Override
    public IIOMetadata getStreamMetadata() {
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>For a still image this decodes the image at full size, as the EXIF block and ICC profile
     * come from the decoder; for a frame of an animation it decodes the animation up to that frame.
     */
    @Override
    public IIOMetadata getImageMetadata(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        var summary = info();
        try {
            if (summary.animated()) {
                frame(imageIndex);
                var timing = timings.get(imageIndex);
                var frame = new SmaltImageMetadata.Frame(imageIndex, timing[0] / 1000, timing[1] / 1000,
                        animation.loopCount());
                return new SmaltImageMetadata(summary.format(), summary.width(), summary.height(), summary.hasAlpha(),
                        false, summary.orientation(), frame, Optional.empty(), Optional.empty(), Optional.empty());
            }
            var decoded = paged() ? page(imageIndex) : still();
            return new SmaltImageMetadata(summary.format(), decoded.sourceWidth(), decoded.sourceHeight(),
                    decoded.picture().hasAlpha(), isGray(decoded.picture()),
                    decoded.orientation(), null, decoded.exif(), decoded.iccProfile(), decoded.xmp(),
                    decoded.density());
        } catch (MediaException exception) {
            throw failure(exception);
        }
    }

    @Override
    public BufferedImage read(int imageIndex, ImageReadParam param) throws IOException {
        checkIndex(imageIndex);
        clearAbortRequest();
        processImageStarted(imageIndex);
        try {
            var selection = select(imageIndex, param);
            var bandSelection = param != null && (param.getSourceBands() != null || param.getDestinationBands() != null);
            var destination = getDestination(param, selection.types.iterator(), selection.width, selection.height);
            var sourceRegion = new Rectangle();
            var destinationRegion = new Rectangle();
            computeRegions(param, selection.width, selection.height, destination, sourceRegion, destinationRegion);
            selection.toPictureCoordinates(sourceRegion);
            RowListenerAdapter progress = new RowListenerAdapter();
            if (bandSelection) {
                var layout = selection.layout;
                var sourceBands = param.getSourceBands() != null ? param.getSourceBands()
                        : identity(layout.channels.length);
                var destinationBands = param.getDestinationBands() != null ? param.getDestinationBands()
                        : identity(sourceBands.length);
                checkReadParamBandSettings(param, layout.channels.length, destination.getSampleModel().getNumBands());
                var raster = destination.getRaster();
                var child = raster.createWritableChild(raster.getMinX(), raster.getMinY(), raster.getWidth(),
                        raster.getHeight(), raster.getMinX(), raster.getMinY(), destinationBands);
                BufferedImages.copyBands(layout.depth == 16 ? widen(selection.picture) : selection.picture,
                        selection.pictureWidth(), selection.pictureHeight(), sourceRegion, selection.xStep, selection.yStep, layout.channels, layout.depth, sourceBands,
                        child, destinationRegion.x, destinationRegion.y, progress);
            } else {
                BufferedImages.copy(copySource(selection.picture, destination), selection.pictureWidth(),
                        selection.pictureHeight(), sourceRegion,
                        selection.xStep, selection.yStep, destination, destinationRegion.x, destinationRegion.y,
                        progress);
            }
            finishRead(imageIndex);
            return destination;
        } catch (MediaException exception) {
            throw failure(exception);
        }
    }

    /**
     * Returns {@code true}: every image reads as a raster.
     *
     * @return {@code true}
     */
    @Override
    public boolean canReadRaster() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The raster holds the bands of the first type {@link #getImageTypes(int)} returns (16-bit samples for
     * a deep image), or only the selected source bands in the order selected, in that type's sample model.
     * Its upper left corner is the destination offset of {@code param}, the destination bands are ignored,
     * and its size is that of the source region clipped to the image and subsampled.
     *
     * @throws IllegalArgumentException if the source bands select a band the image does not have
     */
    @Override
    public Raster readRaster(int imageIndex, ImageReadParam param) throws IOException {
        checkIndex(imageIndex);
        clearAbortRequest();
        processImageStarted(imageIndex);
        try {
            var selection = select(imageIndex, param);
            var layout = selection.layout;
            var sourceBands = param != null && param.getSourceBands() != null ? param.getSourceBands()
                    : identity(layout.channels.length);
            for (var band : sourceBands) {
                if (band < 0 || band >= layout.channels.length) {
                    throw new IllegalArgumentException("source band " + band + " of an image of "
                            + layout.channels.length + " bands");
                }
            }
            var sourceRegion = getSourceRegion(param, selection.width, selection.height);
            var width = (sourceRegion.width + selection.xStep - 1) / selection.xStep;
            var height = (sourceRegion.height + selection.yStep - 1) / selection.yStep;
            var origin = param == null ? new Point() : param.getDestinationOffset();
            var model = selection.types.getFirst().getSampleModel(width, height).createSubsetSampleModel(sourceBands);
            WritableRaster raster = Raster.createWritableRaster(model, origin);
            selection.toPictureCoordinates(sourceRegion);
            var bands = identity(sourceBands.length);
            var channels = new int[sourceBands.length];
            for (var k = 0; k < channels.length; k++) {
                channels[k] = layout.channels[sourceBands[k]];
            }
            BufferedImages.copyBands(layout.depth == 16 ? widen(selection.picture) : selection.picture,
                    selection.pictureWidth(), selection.pictureHeight(), sourceRegion,
                    selection.xStep, selection.yStep, channels, layout.depth, bands, raster, origin.x, origin.y,
                    new RowListenerAdapter());
            finishRead(imageIndex);
            return raster;
        } catch (MediaException exception) {
            throw failure(exception);
        }
    }

    /**
     * Returns {@code true}: the reader reports the thumbnails the class documents.
     *
     * @return {@code true}
     */
    @Override
    public boolean readerSupportsThumbnails() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Listing the thumbnails of a still image or page decodes it, as the EXIF block comes from the
     * decoder; a frame of an animation has none.
     */
    @Override
    public int getNumThumbnails(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return thumbnails(imageIndex).size();
    }

    @Override
    public int getThumbnailWidth(int imageIndex, int thumbnailIndex) throws IOException {
        return thumbnail(imageIndex, thumbnailIndex).width();
    }

    @Override
    public int getThumbnailHeight(int imageIndex, int thumbnailIndex) throws IOException {
        return thumbnail(imageIndex, thumbnailIndex).height();
    }

    @Override
    public BufferedImage readThumbnail(int imageIndex, int thumbnailIndex) throws IOException {
        var thumbnail = thumbnail(imageIndex, thumbnailIndex);
        processThumbnailStarted(imageIndex, thumbnailIndex);
        try {
            var picture = switch (thumbnail) {
                case ImageThumbnail.Item item -> HeifImageCodec.find(item.format())
                        .decodeAuxiliaryImage(source(), item.index(), ImageDecoderOptions.defaults()).picture();
                case ImageThumbnail.Embedded embedded -> embedded.decode();
            };
            var image = BufferedImages.toBufferedImage(BufferedImages.toEightBitRgba(picture));
            processThumbnailProgress(100.0f);
            processThumbnailComplete();
            return image;
        } catch (MediaException exception) {
            throw failure(exception);
        }
    }

    @Override
    public void reset() {
        super.reset();
        clear();
    }

    @Override
    public void dispose() {
        clear();
    }

    /**
     * Decodes the picture a read of an image needs and describes its geometry.
     *
     * @param imageIndex the image
     * @param param the read parameters, or {@code null}
     * @return the picture, its types and its coordinates
     * @throws IOException if the input cannot be read
     */
    private Selection select(int imageIndex, ImageReadParam param) throws IOException {
        var summary = info();
        var paged = paged();
        var width = paged ? page(imageIndex).picture().width() : summary.width();
        var height = paged ? page(imageIndex).picture().height() : summary.height();
        var xStep = param == null ? 1 : param.getSourceXSubsampling();
        var yStep = param == null ? 1 : param.getSourceYSubsampling();
        Picture picture;
        List<ImageTypeSpecifier> types;
        Layout layout;
        // a source region of a still image read without subsampling decodes only that region
        Rectangle decoded = null;
        if (summary.animated()) {
            picture = frame(imageIndex).picture();
            types = animatedTypes();
            layout = new Layout(BufferedImages.channelsOf(false, true), 8);
        } else {
            if (paged) {
                picture = page(imageIndex).picture();
            } else {
                var region = still == null && xStep == 1 && yStep == 1 ? getSourceRegion(param, width, height)
                        : null;
                if (region != null && !region.isEmpty() && (region.width < width || region.height < height)) {
                    picture = Images.decode(source(), DEEP.toBuilder().region(region.x, region.y,
                            region.width, region.height).build()).picture();
                    decoded = region;
                } else {
                    picture = decode(width, height, xStep, yStep);
                }
            }
            types = typesFor(picture);
            layout = layoutOf(picture);
        }
        return new Selection(picture, types, layout, width, height, xStep, yStep, decoded);
    }

    /**
     * Reports the end of a read and moves the minimum index of a forward-only input.
     *
     * @param imageIndex the image read
     */
    private void finishRead(int imageIndex) {
        if (abortRequested()) {
            processReadAborted();
        } else {
            processImageComplete();
        }
        if (seekForwardOnly) {
            minIndex = imageIndex;
        }
    }

    /**
     * Returns the indices {@code 0} to {@code count - 1}.
     *
     * @param count the number of indices
     * @return the indices
     */
    private static int[] identity(int count) {
        var bands = new int[count];
        for (var band = 0; band < count; band++) {
            bands[band] = band;
        }
        return bands;
    }

    /**
     * Decodes the still image for a read, at a reduced size when the read subsamples it.
     *
     * @param width the image width
     * @param height the image height
     * @param xStep the horizontal subsampling
     * @param yStep the vertical subsampling
     * @return the picture, at least {@code ceil(width / xStep)} by {@code ceil(height / yStep)}
     * @throws IOException if the input cannot be read
     */
    private Picture decode(int width, int height, int xStep, int yStep) throws IOException {
        if (still != null || (xStep == 1 && yStep == 1)) {
            return still().picture();
        }
        var minWidth = (width + xStep - 1) / xStep;
        var minHeight = (height + yStep - 1) / yStep;
        var decoded = Images.decode(source(), DEEP.toBuilder().minSize(minWidth, minHeight).build());
        if (decoded.picture().width() == width && decoded.picture().height() == height) {
            still = decoded;
        }
        return decoded.picture();
    }

    /**
     * Returns the full-size decode of the still image, decoding it on first use.
     *
     * @return the decoded image
     * @throws IOException if the input cannot be read
     */
    private DecodedImage still() throws IOException {
        if (still == null) {
            try {
                still = Images.decode(source(), DEEP);
            } catch (MediaException exception) {
                throw failure(exception);
            }
        }
        return still;
    }

    /**
     * Returns whether the input is a multi-page image read page by page.
     *
     * @return {@code true} when the image does not animate and holds more than one image
     * @throws IOException if the input cannot be read
     */
    private boolean paged() throws IOException {
        var summary = info();
        return !summary.animated() && summary.frameCount() > 1;
    }

    /**
     * Returns the summary of a page.
     *
     * @param index the page index
     * @return the summary
     * @throws IOException if the input cannot be read
     */
    private ImageInfo pageInfo(int index) throws IOException {
        try {
            return pages().probe(index);
        } catch (MediaException exception) {
            throw failure(exception);
        }
    }

    /**
     * Returns the full-size decode of a page, decoding it unless it is the last page decoded.
     *
     * @param index the page index
     * @return the decoded page
     * @throws IOException if the input cannot be read
     */
    private DecodedImage page(int index) throws IOException {
        if (index != pageIndex) {
            try {
                page = pages().read(index);
                pageIndex = index;
            } catch (MediaException exception) {
                throw failure(exception);
            }
        }
        return page;
    }

    /**
     * Returns the pages of the input, opening them on first use.
     *
     * @return the page reader
     * @throws IOException if the input cannot be read
     */
    private PageReader pages() throws IOException {
        if (pages == null) {
            try {
                pages = Images.openPages(source(), DEEP);
            } catch (MediaException exception) {
                throw failure(exception);
            }
        }
        return pages;
    }

    /**
     * Returns a frame of the animation, decoding the animation up to it.
     *
     * @param index the frame index
     * @return the frame, valid until the next frame is decoded
     * @throws IOException if the input cannot be read
     * @throws IndexOutOfBoundsException if the animation has fewer frames
     */
    private AnimationFrame frame(int index) throws IOException {
        if (index == currentIndex) {
            return current;
        }
        try {
            if (animation == null || index < currentIndex) {
                closeAnimation();
                animation = Images.openAnimation(source());
            }
            while (currentIndex < index) {
                var frame = animation.read();
                if (frame == null) {
                    frameCount = currentIndex + 1;
                    throw new IndexOutOfBoundsException("frame " + index + " of an animation of " + frameCount + " frames");
                }
                currentIndex++;
                current = frame;
                if (currentIndex == timings.size()) {
                    timings.add(new long[]{frame.timestampMicros(), frame.durationMicros()});
                }
            }
            return current;
        } catch (MediaException exception) {
            throw failure(exception);
        }
    }

    /**
     * Returns the thumbnails of an image, listing them on first use.
     *
     * @param imageIndex the image
     * @return the thumbnails, in the order the class documents
     * @throws IOException if the input cannot be read
     */
    private List<ImageThumbnail> thumbnails(int imageIndex) throws IOException {
        var listed = thumbnails.get(imageIndex);
        if (listed != null) {
            return listed;
        }
        var result = new ArrayList<ImageThumbnail>();
        var summary = info();
        if (!summary.animated()) {
            var paged = paged();
            var format = summary.format();
            if (!paged && (format == ImageFormat.AVIF || format == ImageFormat.HEIC)) {
                var codec = HeifImageCodec.find(format);
                if (codec != null) {
                    try {
                        for (var auxiliary : codec.auxiliaryImages(source(), MediaLimits.DEFAULT)) {
                            if (auxiliary.type() == AuxiliaryImageType.THUMBNAIL) {
                                result.add(new ImageThumbnail.Item(format, auxiliary.index(), auxiliary.width(),
                                        auxiliary.height()));
                            }
                        }
                    } catch (MediaException exception) {
                        throw failure(exception);
                    }
                }
            }
            var decoded = paged ? page(imageIndex) : still();
            decoded.exif().flatMap(ExifThumbnails::find).ifPresent(result::add);
        }
        var list = List.copyOf(result);
        thumbnails.put(imageIndex, list);
        return list;
    }

    /**
     * Returns a thumbnail of an image.
     *
     * @param imageIndex the image
     * @param thumbnailIndex the thumbnail
     * @return the thumbnail
     * @throws IndexOutOfBoundsException if the image or the thumbnail does not exist
     * @throws IOException if the input cannot be read
     */
    private ImageThumbnail thumbnail(int imageIndex, int thumbnailIndex) throws IOException {
        checkIndex(imageIndex);
        var list = thumbnails(imageIndex);
        if (thumbnailIndex < 0 || thumbnailIndex >= list.size()) {
            throw new IndexOutOfBoundsException("thumbnail " + thumbnailIndex + " of " + list.size());
        }
        return list.get(thumbnailIndex);
    }

    /**
     * Returns the image summary, reading the headers on first use.
     *
     * @return the summary
     * @throws IOException if the input cannot be read or is not a supported image
     */
    private ImageInfo info() throws IOException {
        if (info == null) {
            try {
                info = Images.probe(source());
            } catch (MediaException exception) {
                throw failure(exception);
            }
        }
        return info;
    }

    /**
     * Returns the source over the input, creating it on first use.
     *
     * @return the source
     * @throws IllegalStateException if no input is set
     * @throws IOException if the input cannot be read
     */
    private MediaSource source() throws IOException {
        if (source == null) {
            if (input == null) {
                throw new IllegalStateException("no input set");
            }
            source = ImageInputStreamSource.of((ImageInputStream) input);
        }
        return source;
    }

    /**
     * Checks an image index against the input.
     *
     * @param imageIndex the index
     * @throws IndexOutOfBoundsException if the index is negative, below the minimum index of a
     * forward-only input, or past the last image
     * @throws IOException if the input cannot be read
     */
    private void checkIndex(int imageIndex) throws IOException {
        if (imageIndex < minIndex) {
            throw new IndexOutOfBoundsException("image index " + imageIndex + " is below " + minIndex);
        }
        var summary = info();
        var count = summary.animated() ? Math.max(summary.frameCount(), frameCount) : paged() ? summary.frameCount() : 1;
        if (count >= 0 && imageIndex >= count) {
            throw new IndexOutOfBoundsException("image index " + imageIndex + " of " + count + " images");
        }
    }

    /**
     * Returns the band layout of a decoded still picture, the bands of its first image type.
     *
     * @param picture the decoded picture
     * @return the layout
     */
    private Layout layoutOf(Picture picture) {
        var alpha = picture.hasAlpha();
        if (BufferedImages.isDeep(picture)) {
            return new Layout(BufferedImages.channelsOf(isGray(picture), alpha), 16);
        }
        var gray = !alpha && BufferedImages.isMonochrome(picture);
        return new Layout(BufferedImages.channelsOf(gray, alpha), 8);
    }

    /**
     * Returns the image types of a decoded still picture, the lossless one first.
     *
     * @param picture the decoded picture
     * @return the types
     */
    private List<ImageTypeSpecifier> typesFor(Picture picture) {
        var types = new ArrayList<ImageTypeSpecifier>();
        var alpha = picture.hasAlpha();
        int preferred;
        if (BufferedImages.isDeep(picture)) {
            var gray = isGray(picture);
            types.add(deepType(gray, alpha));
            preferred = alpha ? BufferedImage.TYPE_INT_ARGB : gray ? BufferedImage.TYPE_BYTE_GRAY
                    : BufferedImage.TYPE_INT_RGB;
        } else {
            preferred = BufferedImages.imageTypeOf(picture);
        }
        types.add(ImageTypeSpecifier.createFromBufferedImageType(preferred));
        switch (preferred) {
            case BufferedImage.TYPE_INT_ARGB ->
                    types.add(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_4BYTE_ABGR));
            case BufferedImage.TYPE_BYTE_GRAY -> {
                types.add(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB));
                types.add(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_ARGB));
            }
            default -> {
                types.add(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_3BYTE_BGR));
                types.add(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_ARGB));
            }
        }
        return types;
    }

    /**
     * Returns whether a picture is gray, as {@link BufferedImages#isGray(Picture)} decides, converting a deep
     * picture at most once.
     *
     * @param picture the picture to inspect
     * @return {@code true} when the picture is gray
     */
    private boolean isGray(Picture picture) {
        if (BufferedImages.isMonochrome(picture) || !BufferedImages.isDeep(picture)) {
            return BufferedImages.isMonochrome(picture);
        }
        widen(picture);
        return widenedGray;
    }

    /**
     * Returns the 16-bit RGBA form of a deep picture, converting it once for the reads that use it.
     *
     * @param picture the deep picture
     * @return its 16-bit RGBA form
     */
    private Rgba16Picture widen(Picture picture) {
        if (picture != widened) {
            var wide = ImageOps.toRgba16(picture);
            widenedGray = BufferedImages.isMonochrome(picture) || BufferedImages.isGray(wide);
            widenedRgba = wide;
            widened = picture;
        }
        return widenedRgba;
    }

    /**
     * Returns the picture {@link BufferedImages#copy} reads into a destination: the 16-bit RGBA form of a deep
     * picture copied at 16 bits, and the picture itself otherwise, so that 8-bit destinations get the
     * decoders' default reduction.
     *
     * @param picture the decoded picture
     * @param destination the destination image
     * @return the picture to copy
     */
    private Picture copySource(Picture picture, BufferedImage destination) {
        if (!BufferedImages.isDeep(picture) || !BufferedImages.isSixteenBitComponent(destination)) {
            return picture;
        }
        var grayDestination = destination.getColorModel().getNumColorComponents() == 1;
        return !grayDestination || isGray(picture) ? widen(picture) : picture;
    }

    /**
     * Returns the 16-bit image type of a deep picture.
     *
     * @param gray whether the picture is gray
     * @param alpha whether it has alpha
     * @return {@link BufferedImage#TYPE_USHORT_GRAY}, or an interleaved unsigned short type of gray and alpha,
     * RGB or RGBA samples
     */
    private static ImageTypeSpecifier deepType(boolean gray, boolean alpha) {
        if (gray) {
            return alpha ? ImageTypeSpecifier.createGrayscale(16, DataBuffer.TYPE_USHORT, false, false)
                    : ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_USHORT_GRAY);
        }
        var space = ColorSpace.getInstance(ColorSpace.CS_sRGB);
        return alpha
                ? ImageTypeSpecifier.createInterleaved(space, new int[]{0, 1, 2, 3}, DataBuffer.TYPE_USHORT, true, false)
                : ImageTypeSpecifier.createInterleaved(space, new int[]{0, 1, 2}, DataBuffer.TYPE_USHORT, false, false);
    }

    /**
     * Returns the image types of an animation frame, which always has an alpha channel.
     *
     * @return the types
     */
    private static List<ImageTypeSpecifier> animatedTypes() {
        return List.of(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_ARGB),
                ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_4BYTE_ABGR));
    }

    /**
     * Wraps a Smalt failure for ImageIO.
     *
     * @param exception the failure
     * @return the exception to throw
     */
    private static IIOException failure(MediaException exception) {
        return new IIOException(exception.getMessage(), exception);
    }

    /**
     * Forgets everything read from the current input.
     */
    private void clear() {
        closeAnimation();
        if (pages != null) {
            pages.close();
            pages = null;
        }
        pageIndex = -1;
        page = null;
        source = null;
        info = null;
        still = null;
        timings.clear();
        frameCount = -1;
        thumbnails.clear();
        widened = null;
        widenedRgba = null;
    }

    /**
     * Closes the open animation, if any.
     */
    private void closeAnimation() {
        if (animation != null) {
            animation.close();
            animation = null;
        }
        currentIndex = -1;
        current = null;
    }

    /**
     * Reports copy progress to the read listeners and stops the copy on an abort request.
     */
    private final class RowListenerAdapter implements BufferedImages.RowListener {
        @Override
        public boolean rowDone(int row, int total) {
            processImageProgress(100.0f * (row + 1) / total);
            return !abortRequested();
        }
    }

    /**
     * Describes the bands of an image.
     *
     * @param channels the RGBA channel of each band
     * @param depth the bits per sample, 8 or 16
     */
    private record Layout(int[] channels, int depth) {
    }

    /**
     * Holds the picture a read uses, with its types and geometry.
     *
     * @param picture the decoded picture
     * @param types the image types, the default first
     * @param layout the bands of the first type
     * @param width the image width
     * @param height the image height
     * @param xStep the horizontal subsampling
     * @param yStep the vertical subsampling
     * @param decoded the region the picture holds when only a region was decoded, or {@code null}
     */
    private record Selection(Picture picture, List<ImageTypeSpecifier> types, Layout layout, int width, int height,
                             int xStep, int yStep, Rectangle decoded) {
        /**
         * {@return the width the coordinates of a source region refer to after {@link #toPictureCoordinates}}
         */
        int pictureWidth() {
            return decoded == null ? width : decoded.width;
        }

        /**
         * {@return the height the coordinates of a source region refer to after {@link #toPictureCoordinates}}
         */
        int pictureHeight() {
            return decoded == null ? height : decoded.height;
        }

        /**
         * Moves a source region into the coordinates of the decoded region.
         *
         * @param region the source region, in image coordinates; modified
         */
        void toPictureCoordinates(Rectangle region) {
            if (decoded != null) {
                region.translate(-decoded.x, -decoded.y);
            }
        }
    }
}
