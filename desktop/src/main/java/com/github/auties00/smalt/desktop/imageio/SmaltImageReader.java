package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.AnimationFrame;
import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.PageReader;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.io.MediaSource;

import javax.imageio.IIOException;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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
 * <p>Images read as {@link BufferedImage#TYPE_INT_ARGB} when they have alpha,
 * {@link BufferedImage#TYPE_BYTE_GRAY} when they are monochrome and
 * {@link BufferedImage#TYPE_INT_RGB} otherwise, unless the {@link ImageReadParam} names another
 * destination type or image. The EXIF orientation is reported in the metadata and never applied,
 * as the JDK's plug-ins do.
 *
 * <p>{@link ImageReadParam} source regions, subsampling (with offsets), destination offsets,
 * destination images and destination types are honored; band selection is not supported. When the
 * read subsamples a still image, the decoder is asked for a picture of at least the subsampled
 * size ({@link ImageDecoderOptions.Builder#minSize(int, int)}), which lets decoders that shrink while
 * decoding (JPEG, for example) skip the full-size picture.
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
                    decoded.picture().hasAlpha(), BufferedImages.isMonochrome(decoded.picture()),
                    decoded.orientation(), null, decoded.exif(), decoded.iccProfile(), decoded.density());
        } catch (MediaException exception) {
            throw failure(exception);
        }
    }

    @Override
    public BufferedImage read(int imageIndex, ImageReadParam param) throws IOException {
        checkIndex(imageIndex);
        var summary = info();
        clearAbortRequest();
        processImageStarted(imageIndex);
        try {
            var paged = paged();
            var width = paged ? page(imageIndex).picture().width() : summary.width();
            var height = paged ? page(imageIndex).picture().height() : summary.height();
            var xStep = param == null ? 1 : param.getSourceXSubsampling();
            var yStep = param == null ? 1 : param.getSourceYSubsampling();
            Picture picture;
            List<ImageTypeSpecifier> types;
            if (summary.animated()) {
                picture = frame(imageIndex).picture();
                types = animatedTypes();
            } else if (paged) {
                picture = page(imageIndex).picture();
                types = typesFor(picture);
            } else {
                picture = decode(width, height, xStep, yStep);
                types = typesFor(picture);
            }
            checkBands(param);
            var destination = getDestination(param, types.iterator(), width, height);
            var sourceRegion = new Rectangle();
            var destinationRegion = new Rectangle();
            computeRegions(param, width, height, destination, sourceRegion, destinationRegion);
            BufferedImages.copy(picture, width, height, sourceRegion, xStep, yStep, destination,
                    destinationRegion.x, destinationRegion.y, (row, total) -> {
                        processImageProgress(100.0f * (row + 1) / total);
                        return !abortRequested();
                    });
            if (abortRequested()) {
                processReadAborted();
            } else {
                processImageComplete();
            }
            if (seekForwardOnly) {
                minIndex = imageIndex;
            }
            return destination;
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
     * Rejects band selection, which the reader does not support.
     *
     * @param param the read parameters, or {@code null}
     * @throws IllegalArgumentException if the parameters select source or destination bands
     */
    private static void checkBands(ImageReadParam param) {
        if (param != null && (param.getSourceBands() != null || param.getDestinationBands() != null)) {
            throw new IllegalArgumentException("band selection is not supported by the Smalt image reader");
        }
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
        var decoded = Images.decode(source(), ImageDecoderOptions.builder().minSize(minWidth, minHeight).build());
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
                still = Images.decode(source());
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
                pages = Images.openPages(source());
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
     * Returns the image types of a decoded still picture, the lossless one first.
     *
     * @param picture the decoded picture
     * @return the types
     */
    private static List<ImageTypeSpecifier> typesFor(Picture picture) {
        var preferred = BufferedImages.imageTypeOf(picture);
        var types = new ArrayList<ImageTypeSpecifier>();
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
}
