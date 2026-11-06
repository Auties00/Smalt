package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.AnimationWriter;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.PageWriter;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.io.ByteArrayMediaSink;
import com.github.auties00.smalt.io.MediaSink;
import org.w3c.dom.Node;

import javax.imageio.IIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOInvalidTreeException;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataFormatImpl;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Rectangle;
import java.awt.color.ICC_Profile;
import java.awt.image.Raster;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Writes images in one format through the Smalt codec registry ({@link Images}) for ImageIO.
 *
 * <p>An image is converted to a picture keeping its precision: an image of straight 16-bit components in
 * the sRGB or a gray color space ({@link java.awt.image.BufferedImage#TYPE_USHORT_GRAY}, or a 16-bit PNG as
 * the JDK's reader returns it) to 16-bit samples, every other image to 8-bit RGBA with
 * {@link BufferedImages#toPicture(java.awt.image.BufferedImage)} (keeping alpha when its color model has
 * alpha). The writer also writes rasters ({@link #canWriteRasters()}): a raster of one to four bands is
 * gray, gray and alpha, RGB or RGBA, its integer samples rescaled to 8 bits, or to 16 bits when a band holds
 * more than 8 bits, and its float samples read as the range 0 to 1. The picture is encoded with
 * {@link Images#encode(Picture, ImageFormat, ImageEncoderOptions)}, under the options
 * {@link SmaltImageWriteParam} documents; a format that holds fewer bits per sample than the picture reduces
 * it. {@link ImageWriteParam} source regions and subsampling (with offsets) are honored; thumbnails are not
 * written.
 *
 * <p>The image metadata ({@link IIOImage#getMetadata()}) gives the EXIF block, the ICC profile, the XMP
 * packet and the orientation the file records, as {@link ImageEncoderOptions} describes for each format:
 * a {@link SmaltImageMetadata} (as {@link SmaltImageReader} returns it, or as
 * {@link #getDefaultImageMetadata} returns it to fill), or metadata of another plug-in that
 * {@link #convertImageMetadata} converts. The ICC profile describes the color space of the image's
 * samples, which are written unchanged, as the reader returns them.
 *
 * <p>When the registry encodes animations of the format ({@link Images#isAnimationEncodable(ImageFormat)},
 * true for WebP, APNG and GIF), the writer also writes sequences: every image written between
 * {@link #prepareWriteSequence(IIOMetadata)} and {@link #endWriteSequence()} becomes a frame of one
 * animation, encoded with {@link Images#openAnimationWriter(ImageFormat, int, int, ImageEncoderOptions,
 * MediaSink)} under the options and metadata of the first frame. The first frame fixes the canvas size,
 * and every later frame must have it. A frame is shown for the delay its image metadata carries: the
 * {@code delayMillis} of a {@link SmaltImageMetadata}, or the {@code delayTime} of the JDK GIF reader's
 * metadata; an image without either is shown for {@value #DEFAULT_DELAY_MILLIS} milliseconds. The animation
 * plays as many times as the first frame's metadata says (the {@code loopCount} of a
 * {@link SmaltImageMetadata}, or the {@code NETSCAPE2.0} loop count of GIF metadata), and forever otherwise.
 *
 * <p>When the format has no animation encoder but the registry encodes multi-page files of it
 * ({@link Images#isPageEncodable(ImageFormat)}, true for TIFF and ICO), every image of a sequence becomes a
 * page, encoded with {@link Images#openPageWriter(ImageFormat, ImageEncoderOptions, MediaSink)} under the
 * compression of the first page's parameters: each page may have its own size and type and keeps its own
 * metadata, its pixel density included. Either way the file is written to the output when the sequence
 * ends.
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
     * The name of the JDK JPEG plug-in's image metadata format.
     */
    private static final String JPEG_IMAGE_FORMAT = "javax_imageio_jpeg_image_1.0";

    /**
     * The name of the JDK PNG plug-in's image metadata format.
     */
    private static final String PNG_IMAGE_FORMAT = "javax_imageio_png_1.0";

    /**
     * The JPEG marker of an {@code APP1} segment, which holds EXIF or XMP.
     */
    private static final String APP1 = "225";

    /**
     * The identifier opening the EXIF payload of a JPEG {@code APP1} segment.
     */
    private static final byte[] EXIF_IDENTIFIER = {'E', 'x', 'i', 'f', 0, 0};

    /**
     * The namespace opening the XMP payload of a JPEG {@code APP1} segment, with its terminating zero.
     */
    private static final byte[] XMP_IDENTIFIER = "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.US_ASCII);

    /**
     * The keyword of the PNG {@code iTXt} chunk that holds XMP.
     */
    private static final String PNG_XMP_KEYWORD = "XML:com.adobe.xmp";

    /**
     * The buffer the sequence being written is encoded into, or {@code null} outside a sequence.
     */
    private ByteArrayMediaSink sequenceSink;

    /**
     * The animation of the sequence being written, or {@code null} before its first frame or for pages.
     */
    private AnimationWriter sequence;

    /**
     * The pages of the sequence being written, or {@code null} before its first page or for animations.
     */
    private PageWriter pageSequence;

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
     * Returns empty, modifiable {@link SmaltImageMetadata} to fill and pass with an image.
     *
     * @param imageType the type of the image, or {@code null}
     * @param param ignored
     * @return the metadata, with no EXIF block, ICC profile, XMP packet or orientation
     */
    @Override
    public IIOMetadata getDefaultImageMetadata(ImageTypeSpecifier imageType, ImageWriteParam param) {
        return empty(imageType);
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
     * Returns modifiable {@link SmaltImageMetadata} holding what this writer takes from other metadata.
     *
     * <p>The values are those of a {@link SmaltImageMetadata} or of metadata in its native format; of the
     * JDK JPEG plug-in's metadata, the ICC profile of {@code app2ICC} and the EXIF block and XMP packet of
     * its {@code APP1} segments; of the JDK PNG plug-in's metadata, the ICC profile of {@code iCCP} and the
     * XMP packet of the {@code iTXt} entry {@code XML:com.adobe.xmp}; and of any metadata in the standard
     * format, the orientation of {@code Dimension/ImageOrientation} when it is not {@code Normal}, which plug-ins
     * that do not know the orientation report.
     *
     * @param inData the metadata to convert
     * @param imageType the type of the image, or {@code null}
     * @param param ignored
     * @return the metadata, holding nothing when {@code inData} holds nothing this writer takes
     */
    @Override
    public IIOMetadata convertImageMetadata(IIOMetadata inData, ImageTypeSpecifier imageType, ImageWriteParam param) {
        Objects.requireNonNull(inData, "inData");
        var result = empty(imageType);
        if (inData instanceof SmaltImageMetadata smalt) {
            result.copyFrom(smalt);
            return result;
        }
        var names = inData.getMetadataFormatNames() == null ? List.<String>of()
                : Arrays.asList(inData.getMetadataFormatNames());
        try {
            if (inData.isStandardMetadataFormatSupported()) {
                result.mergeTree(IIOMetadataFormatImpl.standardMetadataFormatName,
                        orientationOnly(inData.getAsTree(IIOMetadataFormatImpl.standardMetadataFormatName)));
            }
        } catch (IIOInvalidTreeException | RuntimeException exception) {
            // an orientation of another plug-in that is not a standard name is not taken
        }
        try {
            if (names.contains(SmaltImageMetadataFormat.NAME)) {
                result.mergeTree(SmaltImageMetadataFormat.NAME, inData.getAsTree(SmaltImageMetadataFormat.NAME));
            } else if (names.contains(JPEG_IMAGE_FORMAT)) {
                convertJpeg((IIOMetadataNode) inData.getAsTree(JPEG_IMAGE_FORMAT), result);
            } else if (names.contains(PNG_IMAGE_FORMAT)) {
                convertPng((IIOMetadataNode) inData.getAsTree(PNG_IMAGE_FORMAT), result);
            }
        } catch (IIOInvalidTreeException | RuntimeException exception) {
            return result;
        }
        return result;
    }

    /**
     * Returns {@code true}: the writer writes rasters as the class documents.
     *
     * @return {@code true}
     */
    @Override
    public boolean canWriteRasters() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The stream is written from its current position and not closed.
     *
     * @throws IllegalArgumentException if the source region selects no pixel, or a raster has no band, more
     * than four or signed 16-bit samples
     * @throws IIOException if the metadata's ICC profile or EXIF block is invalid, or encoding fails
     */
    @Override
    public void write(IIOMetadata streamMetadata, IIOImage image, ImageWriteParam param) throws IOException {
        if (output == null) {
            throw new IllegalStateException("no output set");
        }
        Objects.requireNonNull(image, "image");
        clearAbortRequest();
        processImageStarted(0);
        var picture = picture(image, param);
        var options = withMetadata(SmaltImageWriteParam.toOptions(param), metadataOf(image), true);
        if (abortRequested()) {
            processWriteAborted();
            return;
        }
        byte[] encoded;
        try {
            encoded = Images.encode(picture, format, options);
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
     * Returns {@code true} when the registry encodes animations or multi-page files of the format.
     *
     * @return whether sequences can be written
     */
    @Override
    public boolean canWriteSequence() {
        return Images.isAnimationEncodable(format) || Images.isPageEncodable(format);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The stream metadata is ignored.
     *
     * @throws IllegalStateException if no output is set or a sequence is already being
     * written
     * @throws UnsupportedOperationException if the format has no animation or multi-page encoder
     */
    @Override
    public void prepareWriteSequence(IIOMetadata streamMetadata) throws IOException {
        if (output == null) {
            throw new IllegalStateException("no output set");
        }
        if (!canWriteSequence()) {
            throw new UnsupportedOperationException(format.name().toLowerCase(Locale.ROOT)
                    + " animations and multi-page files are not encodable");
        }
        if (sequenceSink != null) {
            throw new IllegalStateException("a sequence is already being written");
        }
        sequenceSink = MediaSink.memory();
        sequence = null;
        pageSequence = null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The image becomes the next frame of the animation, shown for the delay of its metadata, or the
     * next page of the file.
     *
     * @throws IllegalStateException if no sequence is being written
     * @throws IllegalArgumentException if the source region selects no pixel, a raster is not one the class
     * documents, the frame size differs from the first frame's, or the format cannot hold the page
     * @throws IIOException if the metadata's ICC profile or EXIF block is invalid, or encoding fails
     */
    @Override
    public void writeToSequence(IIOImage image, ImageWriteParam param) throws IOException {
        if (sequenceSink == null) {
            throw new IllegalStateException("no sequence is being written");
        }
        Objects.requireNonNull(image, "image");
        clearAbortRequest();
        processImageStarted(pageSequence != null ? pageSequence.pageCount() : 0);
        var picture = picture(image, param);
        if (abortRequested()) {
            processWriteAborted();
            return;
        }
        var metadata = metadataOf(image);
        try {
            if (Images.isAnimationEncodable(format)) {
                if (sequence == null) {
                    var options = withMetadata(SmaltImageWriteParam.toOptions(param), metadata, false).toBuilder()
                            .loopCount(loopCount(image.getMetadata())).build();
                    sequence = Images.openAnimationWriter(format, picture.width(), picture.height(), options,
                            sequenceSink);
                }
                sequence.write(BufferedImages.toEightBitRgba(picture), delayMillis(image.getMetadata()) * 1000L);
            } else {
                if (pageSequence == null) {
                    pageSequence = Images.openPageWriter(format, SmaltImageWriteParam.toOptions(param), sequenceSink);
                }
                pageSequence.write(page(picture, metadata));
            }
        } catch (MediaException exception) {
            throw new IIOException(exception.getMessage(), exception);
        }
        processImageProgress(100.0f);
        processImageComplete();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The animation or multi-page file is completed and written to the output, from its current position.
     *
     * @throws IllegalStateException if no sequence is being written
     * @throws IIOException if no image was written or encoding fails
     */
    @Override
    public void endWriteSequence() throws IOException {
        if (sequenceSink == null) {
            throw new IllegalStateException("no sequence is being written");
        }
        var animation = sequence;
        var pages = pageSequence;
        var sink = sequenceSink;
        sequence = null;
        pageSequence = null;
        sequenceSink = null;
        if (animation == null && pages == null) {
            throw new IIOException("the sequence holds no image");
        }
        try {
            if (animation != null) {
                animation.finish();
            } else {
                pages.finish();
            }
        } catch (MediaException exception) {
            throw new IIOException(exception.getMessage(), exception);
        } finally {
            if (animation != null) {
                animation.close();
            } else {
                pages.close();
            }
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
        if (pageSequence != null) {
            pageSequence.close();
        }
        sequence = null;
        pageSequence = null;
        sequenceSink = null;
    }

    /**
     * Returns empty writer metadata of an image type.
     *
     * @param imageType the type, or {@code null}
     * @return the metadata
     */
    private SmaltImageMetadata empty(ImageTypeSpecifier imageType) {
        var model = imageType == null ? null : imageType.getColorModel();
        return new SmaltImageMetadata(format, model != null && model.hasAlpha(),
                model != null && model.getNumColorComponents() == 1);
    }

    /**
     * Returns the metadata of an image as this writer reads it.
     *
     * @param image the image to write, with or without metadata
     * @return the metadata, or {@code null} when the image has none
     */
    private SmaltImageMetadata metadataOf(IIOImage image) {
        var metadata = image.getMetadata();
        if (metadata == null) {
            return null;
        }
        if (metadata instanceof SmaltImageMetadata smalt) {
            return smalt;
        }
        return (SmaltImageMetadata) convertImageMetadata(metadata, null, null);
    }

    /**
     * Adds the EXIF block, ICC profile, XMP packet and orientation of metadata to encoding options.
     *
     * @param options the compression options
     * @param metadata the metadata, or {@code null}
     * @param orientation whether to record the orientation
     * @return the options
     * @throws IIOException if the ICC profile or the EXIF block is invalid
     */
    private static ImageEncoderOptions withMetadata(ImageEncoderOptions options, SmaltImageMetadata metadata,
                                                    boolean orientation) throws IIOException {
        if (metadata == null) {
            return options;
        }
        var builder = options.toBuilder();
        var icc = metadata.iccProfile();
        if (icc.isPresent()) {
            builder.colorProfile(profile(icc.get()));
        }
        metadata.xmp().ifPresent(builder::xmp);
        if (orientation) {
            metadata.explicitOrientation().ifPresent(builder::orientation);
        }
        var exif = metadata.exif();
        try {
            exif.ifPresent(builder::exif);
            return builder.build();
        } catch (IllegalArgumentException exception) {
            throw new IIOException("the EXIF block is invalid: " + exception.getMessage(), exception);
        }
    }

    /**
     * Parses an ICC profile.
     *
     * @param icc the profile bytes
     * @return the profile
     * @throws IIOException if the bytes are not a profile
     */
    private static ColorProfile profile(byte[] icc) throws IIOException {
        try {
            return ColorProfile.parse(icc);
        } catch (MediaException | IllegalArgumentException exception) {
            throw new IIOException("the ICC profile is invalid: " + exception.getMessage(), exception);
        }
    }

    /**
     * Returns a page of a multi-page file with the metadata of its image.
     *
     * @param picture the page's pixels
     * @param metadata the metadata, or {@code null}
     * @return the page
     * @throws IIOException if the ICC profile is invalid
     */
    private DecodedImage page(Picture picture, SmaltImageMetadata metadata) throws IIOException {
        if (metadata == null) {
            return new DecodedImage(picture, format, Orientation.NORMAL, picture.width(), picture.height(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }
        var icc = metadata.iccProfile();
        var colorProfile = icc.isPresent() ? Optional.of(profile(icc.get())) : Optional.<ColorProfile>empty();
        return new DecodedImage(picture, format, metadata.orientation(), picture.width(), picture.height(), icc,
                metadata.exif(), metadata.xmp(), colorProfile, metadata.density());
    }

    /**
     * Keeps the {@code Dimension/ImageOrientation} of a standard tree, unless it is {@code Normal}.
     *
     * @param standard the standard tree
     * @return a standard tree holding only the orientation, if any
     */
    private static IIOMetadataNode orientationOnly(Node standard) {
        var root = new IIOMetadataNode(IIOMetadataFormatImpl.standardMetadataFormatName);
        for (var child = standard.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!"Dimension".equals(child.getNodeName())) {
                continue;
            }
            for (var entry = child.getFirstChild(); entry != null; entry = entry.getNextSibling()) {
                // plug-ins that do not know the orientation report Normal, which would override the EXIF block
                if ("ImageOrientation".equals(entry.getNodeName()) && entry instanceof IIOMetadataNode node
                        && !"normal".equalsIgnoreCase(node.getAttribute("value"))) {
                    var dimension = new IIOMetadataNode("Dimension");
                    var copy = new IIOMetadataNode("ImageOrientation");
                    copy.setAttribute("value", node.getAttribute("value"));
                    dimension.appendChild(copy);
                    root.appendChild(dimension);
                }
            }
        }
        return root;
    }

    /**
     * Takes the ICC profile, EXIF block and XMP packet of the JDK JPEG plug-in's metadata.
     *
     * @param root the native tree
     * @param result receives the values
     */
    private static void convertJpeg(IIOMetadataNode root, SmaltImageMetadata result) {
        var icc = root.getElementsByTagName("app2ICC");
        if (icc.getLength() > 0 && ((IIOMetadataNode) icc.item(0)).getUserObject() instanceof ICC_Profile profile) {
            result.setIccProfile(profile.getData());
        }
        var unknown = root.getElementsByTagName("unknown");
        for (var i = 0; i < unknown.getLength(); i++) {
            var node = (IIOMetadataNode) unknown.item(i);
            if (!APP1.equals(node.getAttribute("MarkerTag")) || !(node.getUserObject() instanceof byte[] data)) {
                continue;
            }
            if (startsWith(data, EXIF_IDENTIFIER)) {
                result.setExif(Arrays.copyOfRange(data, EXIF_IDENTIFIER.length, data.length));
            } else if (startsWith(data, XMP_IDENTIFIER)) {
                result.setXmp(Arrays.copyOfRange(data, XMP_IDENTIFIER.length, data.length));
            }
        }
    }

    /**
     * Takes the ICC profile and XMP packet of the JDK PNG plug-in's metadata.
     *
     * @param root the native tree
     * @param result receives the values
     */
    private static void convertPng(IIOMetadataNode root, SmaltImageMetadata result) {
        var icc = root.getElementsByTagName("iCCP");
        if (icc.getLength() > 0 && ((IIOMetadataNode) icc.item(0)).getUserObject() instanceof byte[] profile) {
            result.setIccProfile(profile);
        }
        var text = root.getElementsByTagName("iTXtEntry");
        for (var i = 0; i < text.getLength(); i++) {
            var entry = (IIOMetadataNode) text.item(i);
            if (PNG_XMP_KEYWORD.equals(entry.getAttribute("keyword"))) {
                result.setXmp(entry.getAttribute("text").getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    /**
     * Returns whether bytes start with a prefix.
     *
     * @param data the bytes
     * @param prefix the bytes {@code data} must start with
     * @return {@code true} when {@code data} starts with {@code prefix}
     */
    private static boolean startsWith(byte[] data, byte[] prefix) {
        return data.length >= prefix.length && Arrays.equals(data, 0, prefix.length, prefix, 0, prefix.length);
    }

    /**
     * Converts the selected pixels of an image or raster to a picture.
     *
     * @param image the image to write
     * @param param the parameters selecting the source region and subsampling, or {@code null}
     * @return the picture
     * @throws IllegalArgumentException if the source region selects no pixel, or the raster is not one the
     * class documents
     */
    private static Picture picture(IIOImage image, ImageWriteParam param) {
        if (image.hasRaster()) {
            var raster = image.getRaster();
            return BufferedImages.toPicture(raster, region(raster.getBounds(), param), stepX(param), stepY(param));
        }
        var buffered = BufferedImages.toBufferedImage(image.getRenderedImage());
        var region = region(new Rectangle(0, 0, buffered.getWidth(), buffered.getHeight()), param);
        if (BufferedImages.isSixteenBitComponent(buffered)) {
            return BufferedImages.toPicture((Raster) buffered.getRaster(), region, stepX(param), stepY(param));
        }
        return BufferedImages.toPicture(buffered, region, stepX(param), stepY(param));
    }

    /**
     * Returns the region of an image a write reads.
     *
     * @param bounds the bounds of the image or raster
     * @param param the parameters, or {@code null}
     * @return the source region, moved by the subsampling offsets
     * @throws IllegalArgumentException if the region selects no pixel
     */
    private static Rectangle region(Rectangle bounds, ImageWriteParam param) {
        var region = new Rectangle(bounds);
        if (param != null) {
            if (param.getSourceRegion() != null) {
                region = region.intersection(param.getSourceRegion());
            }
            region.x += param.getSubsamplingXOffset();
            region.y += param.getSubsamplingYOffset();
            region.width -= param.getSubsamplingXOffset();
            region.height -= param.getSubsamplingYOffset();
        }
        if (region.width <= 0 || region.height <= 0) {
            throw new IllegalArgumentException("the source region selects no pixel");
        }
        return region;
    }

    /**
     * {@return the horizontal subsampling of parameters, {@code 1} for none}
     *
     * @param param the parameters, or {@code null}
     */
    private static int stepX(ImageWriteParam param) {
        return param == null ? 1 : param.getSourceXSubsampling();
    }

    /**
     * {@return the vertical subsampling of parameters, {@code 1} for none}
     *
     * @param param the parameters, or {@code null}
     */
    private static int stepY(ImageWriteParam param) {
        return param == null ? 1 : param.getSourceYSubsampling();
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
