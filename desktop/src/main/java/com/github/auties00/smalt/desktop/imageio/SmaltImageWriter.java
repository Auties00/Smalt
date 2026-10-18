package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.AnimationWriter;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.io.ByteArrayMediaSink;
import com.github.auties00.smalt.io.MediaSink;

import javax.imageio.IIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Rectangle;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/**
 * Writes images in one format through the Smalt codec registry ({@link Images}) for ImageIO.
 *
 * <p>The image is converted to an RGBA picture with {@link BufferedImages#toPicture(java.awt.image.BufferedImage)}
 * (keeping alpha when its color model has alpha) and encoded with
 * {@link Images#encode(com.github.auties00.smalt.image.Picture, ImageFormat,
 * com.github.auties00.smalt.image.ImageEncoderOptions)}, under the options
 * {@link SmaltImageWriteParam} documents. {@link ImageWriteParam} source regions and subsampling
 * (with offsets) are honored. The writer supports no metadata of its own, no thumbnails and no
 * rasters.
 *
 * <p>When the registry encodes animations of the format
 * ({@link Images#isAnimationEncodable(ImageFormat)}, true for WebP, APNG and GIF), the writer also writes
 * sequences: every image written between {@link #prepareWriteSequence(IIOMetadata)} and
 * {@link #endWriteSequence()} becomes a frame of one animation, encoded with
 * {@link Images#openAnimationWriter(ImageFormat, int, int,
 * com.github.auties00.smalt.image.ImageEncoderOptions, MediaSink)} under the options of the first
 * frame's parameters. The first frame fixes the canvas size, and every later frame must have it.
 * A frame is shown for the delay its image metadata carries: the {@code delayMillis} of a
 * {@link SmaltImageMetadata}, or the {@code delayTime} of the JDK GIF reader's metadata; an image
 * without either is shown for {@value #DEFAULT_DELAY_MILLIS} milliseconds. The animation plays as
 * many times as the first frame's metadata says (the {@code loopCount} of a
 * {@link SmaltImageMetadata}, or the {@code NETSCAPE2.0} loop count of GIF metadata), and forever
 * otherwise. The file is written to the output when the sequence ends.
 *
 * <p>Instances are not thread-safe.
 */
public final class SmaltImageWriter extends ImageWriter {
    /**
     * The format written.
     */
    private final ImageFormat format;

    /**
     * The display time of a sequence frame whose metadata carries none, in milliseconds.
     */
    static final int DEFAULT_DELAY_MILLIS = 100;

    /**
     * The name of the JDK GIF reader's image metadata format.
     */
    private static final String GIF_IMAGE_FORMAT = "javax_imageio_gif_image_1.0";

    /**
     * The buffer the sequence being written is encoded into, or {@code null} outside a sequence.
     */
    private ByteArrayMediaSink sequenceSink;

    /**
     * The animation of the sequence being written, or {@code null} before its first frame.
     */
    private AnimationWriter sequence;

    /**
     * Constructs a writer.
     *
     * @param provider the provider that created it
     * @param format the format written
     */
    SmaltImageWriter(ImageWriterSpi provider, ImageFormat format) {
        super(provider);
        this.format = Objects.requireNonNull(format, "format");
    }

    /**
     * {@inheritDoc}
     *
     * <p>The output must be an {@link ImageOutputStream}.
     */
    @Override
    public void setOutput(Object output) {
        if (output != null && !(output instanceof ImageOutputStream)) {
            throw new IllegalArgumentException("the output must be an ImageOutputStream, not " + output.getClass().getName());
        }
        super.setOutput(output);
    }

    /**
     * Returns new parameters with lossy compression at {@link SmaltImageWriteParam#DEFAULT_QUALITY}.
     *
     * @return the parameters
     */
    @Override
    public ImageWriteParam getDefaultWriteParam() {
        return new SmaltImageWriteParam(getLocale());
    }

    /**
     * Returns {@code null}: the writer supports no stream metadata.
     *
     * @param param ignored
     * @return {@code null}
     */
    @Override
    public IIOMetadata getDefaultStreamMetadata(ImageWriteParam param) {
        return null;
    }

    /**
     * Returns {@code null}: the writer supports no image metadata.
     *
     * @param imageType ignored
     * @param param ignored
     * @return {@code null}
     */
    @Override
    public IIOMetadata getDefaultImageMetadata(ImageTypeSpecifier imageType, ImageWriteParam param) {
        return null;
    }

    /**
     * Returns {@code null}: the writer supports no stream metadata.
     *
     * @param inData ignored
     * @param param ignored
     * @return {@code null}
     */
    @Override
    public IIOMetadata convertStreamMetadata(IIOMetadata inData, ImageWriteParam param) {
        return null;
    }

    /**
     * Returns {@code null}: the writer supports no image metadata.
     *
     * @param inData ignored
     * @param imageType ignored
     * @param param ignored
     * @return {@code null}
     */
    @Override
    public IIOMetadata convertImageMetadata(IIOMetadata inData, ImageTypeSpecifier imageType, ImageWriteParam param) {
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Metadata is ignored. The stream is written from its current position and not closed.
     *
     * @throws UnsupportedOperationException if {@code image} holds a raster
     * @throws IllegalArgumentException if the source region selects no pixel
     */
    @Override
    public void write(IIOMetadata streamMetadata, IIOImage image, ImageWriteParam param) throws IOException {
        if (output == null) {
            throw new IllegalStateException("no output set");
        }
        Objects.requireNonNull(image, "image");
        if (image.hasRaster()) {
            throw new UnsupportedOperationException("the Smalt image writer does not write rasters");
        }
        clearAbortRequest();
        processImageStarted(0);
        var picture = picture(image, param);
        if (abortRequested()) {
            processWriteAborted();
            return;
        }
        byte[] encoded;
        try {
            encoded = Images.encode(picture, format, SmaltImageWriteParam.toOptions(param));
        } catch (MediaException exception) {
            throw new IIOException(exception.getMessage(), exception);
        }
        if (abortRequested()) {
            processWriteAborted();
            return;
        }
        ((ImageOutputStream) output).write(encoded);
        processImageProgress(100.0f);
        processImageComplete();
    }

    /**
     * Returns {@code true} when the registry encodes animations of the format.
     *
     * @return whether sequences can be written
     */
    @Override
    public boolean canWriteSequence() {
        return Images.isAnimationEncodable(format);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The stream metadata is ignored.
     *
     * @throws IllegalStateException if no output is set or a sequence is already being
     * written
     * @throws UnsupportedOperationException if the format has no animation encoder
     */
    @Override
    public void prepareWriteSequence(IIOMetadata streamMetadata) throws IOException {
        if (output == null) {
            throw new IllegalStateException("no output set");
        }
        if (!canWriteSequence()) {
            throw new UnsupportedOperationException(format.name().toLowerCase(Locale.ROOT)
                    + " animations are not encodable");
        }
        if (sequenceSink != null) {
            throw new IllegalStateException("a sequence is already being written");
        }
        sequenceSink = MediaSink.memory();
        sequence = null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The image becomes the next frame of the animation, shown for the delay of its metadata.
     *
     * @throws IllegalStateException if no sequence is being written
     * @throws UnsupportedOperationException if {@code image} holds a raster
     * @throws IllegalArgumentException if the source region selects no pixel, or the frame
     * size differs from the first frame's
     */
    @Override
    public void writeToSequence(IIOImage image, ImageWriteParam param) throws IOException {
        if (sequenceSink == null) {
            throw new IllegalStateException("no sequence is being written");
        }
        Objects.requireNonNull(image, "image");
        if (image.hasRaster()) {
            throw new UnsupportedOperationException("the Smalt image writer does not write rasters");
        }
        clearAbortRequest();
        processImageStarted(0);
        var picture = picture(image, param);
        if (abortRequested()) {
            processWriteAborted();
            return;
        }
        try {
            if (sequence == null) {
                var options = SmaltImageWriteParam.toOptions(param).toBuilder()
                        .loopCount(loopCount(image.getMetadata())).build();
                sequence = Images.openAnimationWriter(format, picture.width(), picture.height(), options, sequenceSink);
            }
            sequence.write(picture, delayMillis(image.getMetadata()) * 1000L);
        } catch (MediaException exception) {
            throw new IIOException(exception.getMessage(), exception);
        }
        processImageProgress(100.0f);
        processImageComplete();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The animation is completed and written to the output, from its current position.
     *
     * @throws IllegalStateException if no sequence is being written
     * @throws IIOException if no frame was written or encoding fails
     */
    @Override
    public void endWriteSequence() throws IOException {
        if (sequenceSink == null) {
            throw new IllegalStateException("no sequence is being written");
        }
        var animation = sequence;
        var sink = sequenceSink;
        sequence = null;
        sequenceSink = null;
        if (animation == null) {
            throw new IIOException("the sequence holds no frame");
        }
        try (animation) {
            animation.finish();
        } catch (MediaException exception) {
            throw new IIOException(exception.getMessage(), exception);
        }
        ((ImageOutputStream) output).write(sink.toByteArray());
    }

    /**
     * {@inheritDoc}
     *
     * <p>A sequence being written is abandoned.
     */
    @Override
    public void reset() {
        abandonSequence();
        super.reset();
    }

    /**
     * {@inheritDoc}
     *
     * <p>A sequence being written is abandoned.
     */
    @Override
    public void dispose() {
        abandonSequence();
        super.dispose();
    }

    /**
     * Drops the sequence being written, if any, without writing it.
     */
    private void abandonSequence() {
        if (sequence != null) {
            sequence.close();
        }
        sequence = null;
        sequenceSink = null;
    }

    /**
     * Converts the selected pixels of an image to an RGBA picture.
     *
     * @param image the image to write, holding a rendered image
     * @param param the parameters selecting the source region and subsampling, or {@code null}
     * @return the picture
     * @throws IllegalArgumentException if the source region selects no pixel
     */
    private static RgbaPicture picture(IIOImage image, ImageWriteParam param) {
        var buffered = BufferedImages.toBufferedImage(image.getRenderedImage());
        var region = new Rectangle(0, 0, buffered.getWidth(), buffered.getHeight());
        var xStep = 1;
        var yStep = 1;
        if (param != null) {
            if (param.getSourceRegion() != null) {
                region = region.intersection(param.getSourceRegion());
            }
            xStep = param.getSourceXSubsampling();
            yStep = param.getSourceYSubsampling();
            region.x += param.getSubsamplingXOffset();
            region.y += param.getSubsamplingYOffset();
            region.width -= param.getSubsamplingXOffset();
            region.height -= param.getSubsamplingYOffset();
        }
        if (region.width <= 0 || region.height <= 0) {
            throw new IllegalArgumentException("the source region selects no pixel");
        }
        return BufferedImages.toPicture(buffered, region, xStep, yStep);
    }

    /**
     * Returns the display time a frame's metadata asks for.
     *
     * @param metadata the image metadata, or {@code null}
     * @return the delay in milliseconds
     */
    static long delayMillis(IIOMetadata metadata) {
        if (metadata instanceof SmaltImageMetadata smalt) {
            return smalt.delayMillis().orElse(DEFAULT_DELAY_MILLIS);
        }
        var control = gifNode(metadata, "GraphicControlExtension");
        if (control != null && !control.getAttribute("delayTime").isEmpty()) {
            return Long.parseLong(control.getAttribute("delayTime")) * 10;
        }
        return DEFAULT_DELAY_MILLIS;
    }

    /**
     * Returns the number of plays the first frame's metadata asks for.
     *
     * @param metadata the image metadata, or {@code null}
     * @return the number of plays, {@code 0} for forever
     */
    static int loopCount(IIOMetadata metadata) {
        if (metadata instanceof SmaltImageMetadata smalt) {
            return smalt.loopCount().orElse(0);
        }
        var extensions = gifNode(metadata, "ApplicationExtensions");
        if (extensions != null) {
            for (var child = extensions.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof IIOMetadataNode node && "NETSCAPE".equals(node.getAttribute("applicationID"))
                        && node.getUserObject() instanceof byte[] data && data.length >= 3 && data[0] == 1) {
                    return (data[1] & 0xFF) | (data[2] & 0xFF) << 8;
                }
            }
        }
        return 0;
    }

    /**
     * Returns a top-level node of the JDK GIF reader's image metadata.
     *
     * @param metadata the image metadata, or {@code null}
     * @param name the node name
     * @return the node, or {@code null} when the metadata is not GIF metadata or lacks the node
     */
    private static IIOMetadataNode gifNode(IIOMetadata metadata, String name) {
        if (metadata == null || metadata.getMetadataFormatNames() == null
                || !Arrays.asList(metadata.getMetadataFormatNames()).contains(GIF_IMAGE_FORMAT)) {
            return null;
        }
        var root = metadata.getAsTree(GIF_IMAGE_FORMAT);
        for (var child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (name.equals(child.getNodeName()) && child instanceof IIOMetadataNode node) {
                return node;
            }
        }
        return null;
    }
}
