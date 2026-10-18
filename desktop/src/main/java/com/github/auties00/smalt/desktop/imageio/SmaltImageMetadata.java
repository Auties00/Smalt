package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Orientation;
import org.w3c.dom.Node;

import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataFormatImpl;
import javax.imageio.metadata.IIOMetadataNode;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Holds the read-only metadata of one image read by {@link SmaltImageReader}.
 *
 * <p>The native format is {@link SmaltImageMetadataFormat}; the standard format
 * ({@value IIOMetadataFormatImpl#standardMetadataFormatName}) carries the color type, the alpha
 * and the EXIF orientation as {@code Dimension/ImageOrientation}, named as the JDK's TIFF plug-in
 * names the EXIF orientations.
 */
public final class SmaltImageMetadata extends IIOMetadata {
    /**
     * The standard {@code ImageOrientation} name of each EXIF orientation code, indexed by code.
     */
    private static final String[] STANDARD_ORIENTATIONS = {null, "Normal", "FlipH", "Rotate180", "FlipV",
            "FlipHRotate90", "Rotate270", "FlipVRotate90", "Rotate90"};

    /**
     * The image format.
     */
    private final ImageFormat format;

    /**
     * The image width.
     */
    private final int width;

    /**
     * The image height.
     */
    private final int height;

    /**
     * Whether the image has alpha.
     */
    private final boolean hasAlpha;

    /**
     * Whether the image is gray.
     */
    private final boolean gray;

    /**
     * The orientation the image asks to be displayed with.
     */
    private final Orientation orientation;

    /**
     * The frame timing of an animated image, or {@code null}.
     */
    private final Frame frame;

    /**
     * The raw EXIF block, or {@code null}.
     */
    private final byte[] exif;

    /**
     * The raw ICC profile, or {@code null}.
     */
    private final byte[] iccProfile;

    /**
     * Constructs the metadata.
     *
     * @param format the image format
     * @param width the image width
     * @param height the image height
     * @param hasAlpha whether the image has alpha
     * @param gray whether the image is gray
     * @param orientation the orientation the image asks to be displayed with
     * @param frame the frame timing of an animated image, or {@code null}
     * @param exif the raw EXIF block, or empty
     * @param iccProfile the raw ICC profile, or empty
     */
    SmaltImageMetadata(ImageFormat format, int width, int height, boolean hasAlpha, boolean gray,
                       Orientation orientation, Frame frame, Optional<byte[]> exif, Optional<byte[]> iccProfile) {
        super(true, SmaltImageMetadataFormat.NAME, SmaltImageMetadataFormat.class.getName(), null, null);
        this.format = Objects.requireNonNull(format, "format");
        this.width = width;
        this.height = height;
        this.hasAlpha = hasAlpha;
        this.gray = gray;
        this.orientation = Objects.requireNonNull(orientation, "orientation");
        this.frame = frame;
        this.exif = exif.orElse(null);
        this.iccProfile = iccProfile.orElse(null);
    }

    /**
     * Returns the orientation the image asks to be displayed with.
     *
     * @return the orientation, {@link Orientation#NORMAL} when the image declares none
     */
    public Orientation orientation() {
        return orientation;
    }

    /**
     * Returns the raw EXIF block of the image.
     *
     * @return a copy of the block, or empty when the image has none
     */
    public Optional<byte[]> exif() {
        return Optional.ofNullable(exif).map(byte[]::clone);
    }

    /**
     * Returns the raw ICC profile of the image.
     *
     * @return a copy of the profile, or empty when the image has none
     */
    public Optional<byte[]> iccProfile() {
        return Optional.ofNullable(iccProfile).map(byte[]::clone);
    }

    /**
     * Returns the display duration of the frame in milliseconds.
     *
     * @return the duration, or empty for a still image
     */
    public OptionalLong delayMillis() {
        return frame == null ? OptionalLong.empty() : OptionalLong.of(frame.delayMillis());
    }

    /**
     * Returns how many times the animation the frame belongs to plays.
     *
     * @return the number of plays, 0 meaning forever, or empty for a still image
     */
    public OptionalInt loopCount() {
        return frame == null ? OptionalInt.empty() : OptionalInt.of(frame.loopCount());
    }

    /**
     * Returns {@code true}: the metadata describes an image as read and cannot be modified.
     *
     * @return {@code true}
     */
    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Node getAsTree(String formatName) {
        Objects.requireNonNull(formatName, "formatName");
        if (formatName.equals(SmaltImageMetadataFormat.NAME)) {
            return nativeTree();
        }
        if (formatName.equals(IIOMetadataFormatImpl.standardMetadataFormatName)) {
            return getStandardTree();
        }
        throw new IllegalArgumentException("unsupported metadata format " + formatName);
    }

    /**
     * Rejects the change: the metadata is read-only.
     *
     * @param formatName ignored
     * @param root ignored
     * @throws IllegalStateException always
     */
    @Override
    public void mergeTree(String formatName, Node root) {
        throw new IllegalStateException("the metadata is read-only");
    }

    /**
     * Rejects the change: the metadata is read-only.
     *
     * @throws IllegalStateException always
     */
    @Override
    public void reset() {
        throw new IllegalStateException("the metadata is read-only");
    }

    /**
     * Builds the native tree.
     *
     * @return the root node
     */
    private IIOMetadataNode nativeTree() {
        var root = new IIOMetadataNode(SmaltImageMetadataFormat.NAME);
        root.setAttribute("format", format.name().toLowerCase(Locale.ROOT));
        root.setAttribute("width", Integer.toString(width));
        root.setAttribute("height", Integer.toString(height));
        root.setAttribute("hasAlpha", Boolean.toString(hasAlpha));
        root.setAttribute("orientation", Integer.toString(orientation.exifCode()));
        root.setAttribute("animated", Boolean.toString(frame != null));
        if (frame != null) {
            var animation = new IIOMetadataNode("Animation");
            animation.setAttribute("frameIndex", Integer.toString(frame.index()));
            animation.setAttribute("timestampMillis", Long.toString(frame.timestampMillis()));
            animation.setAttribute("delayMillis", Long.toString(frame.delayMillis()));
            animation.setAttribute("loopCount", Integer.toString(frame.loopCount()));
            root.appendChild(animation);
        }
        if (exif != null) {
            var node = new IIOMetadataNode("Exif");
            node.setUserObject(exif.clone());
            root.appendChild(node);
        }
        if (iccProfile != null) {
            var node = new IIOMetadataNode("IccProfile");
            node.setUserObject(iccProfile.clone());
            root.appendChild(node);
        }
        return root;
    }

    @Override
    protected IIOMetadataNode getStandardChromaNode() {
        var chroma = new IIOMetadataNode("Chroma");
        var space = new IIOMetadataNode("ColorSpaceType");
        space.setAttribute("name", gray ? "GRAY" : "RGB");
        chroma.appendChild(space);
        var channels = new IIOMetadataNode("NumChannels");
        channels.setAttribute("value", Integer.toString((gray ? 1 : 3) + (hasAlpha ? 1 : 0)));
        chroma.appendChild(channels);
        return chroma;
    }

    @Override
    protected IIOMetadataNode getStandardDimensionNode() {
        var dimension = new IIOMetadataNode("Dimension");
        var imageOrientation = new IIOMetadataNode("ImageOrientation");
        imageOrientation.setAttribute("value", STANDARD_ORIENTATIONS[orientation.exifCode()]);
        dimension.appendChild(imageOrientation);
        return dimension;
    }

    @Override
    protected IIOMetadataNode getStandardTransparencyNode() {
        var transparency = new IIOMetadataNode("Transparency");
        var alpha = new IIOMetadataNode("Alpha");
        alpha.setAttribute("value", hasAlpha ? "nonpremultiplied" : "none");
        transparency.appendChild(alpha);
        return transparency;
    }

    /**
     * Describes the timing of one frame of an animation.
     *
     * @param index the frame index, from {@code 0}
     * @param timestampMillis when the frame is first shown, in milliseconds from the start
     * @param delayMillis how long the frame is shown, in milliseconds
     * @param loopCount how many times the animation plays, {@code 0} meaning forever
     */
    record Frame(int index, long timestampMillis, long delayMillis, int loopCount) {
    }
}
