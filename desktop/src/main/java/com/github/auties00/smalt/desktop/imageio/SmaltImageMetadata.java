package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.PixelDensity;
import org.w3c.dom.Node;

import javax.imageio.metadata.IIOInvalidTreeException;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataFormatImpl;
import javax.imageio.metadata.IIOMetadataNode;
import java.awt.color.ICC_Profile;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Holds the metadata of one image read by {@link SmaltImageReader}, or of one image to write with
 * {@link SmaltImageWriter}.
 *
 * <p>The native format is {@link SmaltImageMetadataFormat}; the standard format
 * ({@value IIOMetadataFormatImpl#standardMetadataFormatName}) carries the color type, the alpha
 * and the EXIF orientation as {@code Dimension/ImageOrientation}, named as the JDK's TIFF plug-in
 * names the EXIF orientations. The pixel density of the image, when it declares one, gives
 * {@code Dimension/PixelAspectRatio} (the pixel width over its height) and, when it has a physical unit,
 * {@code Dimension/HorizontalPixelSize} and {@code VerticalPixelSize} (the size of a pixel in millimeters).
 *
 * <p>The metadata of a read image is read-only. The metadata a writer returns
 * ({@link SmaltImageWriter#getDefaultImageMetadata}, {@link SmaltImageWriter#convertImageMetadata}) can be
 * modified: {@link #mergeTree(String, Node)} of the native format sets the {@code orientation}, the EXIF
 * block, the ICC profile and the XMP packet that a child carries as its user object (a {@code byte[]}, or
 * for the ICC profile also an {@link ICC_Profile}), the pixel density of a {@code Density} child, and the
 * {@code delayMillis} and {@code loopCount} of an {@code Animation} child; {@code mergeTree} of the standard
 * format sets the orientation of {@code Dimension/ImageOrientation} (its name compared ignoring case); and
 * {@link #reset()} removes them all.
 *
 * <p>Instances are not thread-safe.
 */
public final class SmaltImageMetadata extends IIOMetadata {
    /**
     * The standard {@code ImageOrientation} name of each EXIF orientation code, indexed by code.
     */
    private static final String[] STANDARD_ORIENTATIONS = {null, "Normal", "FlipH", "Rotate180", "FlipV",
            "FlipHRotate90", "Rotate270", "FlipVRotate90", "Rotate90"};

    /**
     * Whether the metadata can be modified.
     */
    private final boolean writable;

    /**
     * The image format.
     */
    private final ImageFormat format;

    /**
     * The image width, or {@code 0} when unknown.
     */
    private final int width;

    /**
     * The image height, or {@code 0} when unknown.
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
     * The orientation the image asks to be displayed with, or {@code null} when a writer leaves it to
     * the EXIF block.
     */
    private Orientation orientation;

    /**
     * The frame timing of an animated image, or {@code null}.
     */
    private Frame frame;

    /**
     * The raw EXIF block, or {@code null}.
     */
    private byte[] exif;

    /**
     * The raw ICC profile, or {@code null}.
     */
    private byte[] iccProfile;

    /**
     * The XMP packet, or {@code null}.
     */
    private byte[] xmp;

    /**
     * The pixel density, or {@code null}.
     */
    private PixelDensity density;

    /**
     * Constructs the read-only metadata of a read image, without an XMP packet.
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
     * @param density the pixel density, or empty
     */
    SmaltImageMetadata(ImageFormat format, int width, int height, boolean hasAlpha, boolean gray,
                       Orientation orientation, Frame frame, Optional<byte[]> exif, Optional<byte[]> iccProfile,
                       Optional<PixelDensity> density) {
        this(format, width, height, hasAlpha, gray, orientation, frame, exif, iccProfile, Optional.empty(), density);
    }

    /**
     * Constructs the read-only metadata of a read image.
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
     * @param xmp the XMP packet, or empty
     * @param density the pixel density, or empty
     */
    SmaltImageMetadata(ImageFormat format, int width, int height, boolean hasAlpha, boolean gray,
                       Orientation orientation, Frame frame, Optional<byte[]> exif, Optional<byte[]> iccProfile,
                       Optional<byte[]> xmp, Optional<PixelDensity> density) {
        super(true, SmaltImageMetadataFormat.NAME, SmaltImageMetadataFormat.class.getName(), null, null);
        this.writable = false;
        this.format = Objects.requireNonNull(format, "format");
        this.width = width;
        this.height = height;
        this.hasAlpha = hasAlpha;
        this.gray = gray;
        this.orientation = Objects.requireNonNull(orientation, "orientation");
        this.frame = frame;
        this.exif = exif.orElse(null);
        this.iccProfile = iccProfile.orElse(null);
        this.xmp = xmp.orElse(null);
        this.density = density.orElse(null);
    }

    /**
     * Constructs empty, modifiable metadata of an image a writer writes.
     *
     * @param format the format written
     * @param hasAlpha whether the image type has alpha
     * @param gray whether the image type is gray
     */
    SmaltImageMetadata(ImageFormat format, boolean hasAlpha, boolean gray) {
        super(true, SmaltImageMetadataFormat.NAME, SmaltImageMetadataFormat.class.getName(), null, null);
        this.writable = true;
        this.format = Objects.requireNonNull(format, "format");
        this.width = 0;
        this.height = 0;
        this.hasAlpha = hasAlpha;
        this.gray = gray;
    }

    /**
     * Returns the orientation the image asks to be displayed with.
     *
     * @return the orientation, {@link Orientation#NORMAL} when the image declares none or a writer's
     * metadata sets none
     */
    public Orientation orientation() {
        return orientation == null ? Orientation.NORMAL : orientation;
    }

    /**
     * Returns the orientation set on writer metadata.
     *
     * @return the orientation, or empty when it is left to the EXIF block
     */
    Optional<Orientation> explicitOrientation() {
        return Optional.ofNullable(orientation);
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
     * Returns the XMP packet of the image, its UTF-8 XML as stored.
     *
     * @return a copy of the packet, or empty when the image has none
     */
    public Optional<byte[]> xmp() {
        return Optional.ofNullable(xmp).map(byte[]::clone);
    }

    /**
     * Returns the pixel density of the image, as {@link com.github.auties00.smalt.image.DecodedImage#density()}
     * reports it.
     *
     * @return the density, or empty when the image declares none or is a frame of an animation
     */
    public Optional<PixelDensity> density() {
        return Optional.ofNullable(density);
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
     * Returns whether the metadata cannot be modified.
     *
     * @return {@code true} for the metadata of a read image, {@code false} for writer metadata
     */
    @Override
    public boolean isReadOnly() {
        return !writable;
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
     * {@inheritDoc}
     *
     * <p>The class documents what each format sets.
     *
     * @throws IllegalStateException if the metadata is read-only
     * @throws IIOInvalidTreeException if a value of the tree is invalid
     */
    @Override
    public void mergeTree(String formatName, Node root) throws IIOInvalidTreeException {
        Objects.requireNonNull(formatName, "formatName");
        Objects.requireNonNull(root, "root");
        if (!writable) {
            throw new IllegalStateException("the metadata is read-only");
        }
        if (formatName.equals(SmaltImageMetadataFormat.NAME)) {
            mergeNative(root);
        } else if (formatName.equals(IIOMetadataFormatImpl.standardMetadataFormatName)) {
            mergeStandard(root);
        } else {
            throw new IllegalArgumentException("unsupported metadata format " + formatName);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>The orientation, the EXIF block, the ICC profile, the XMP packet, the density and the frame timing
     * are removed.
     *
     * @throws IllegalStateException if the metadata is read-only
     */
    @Override
    public void reset() {
        if (!writable) {
            throw new IllegalStateException("the metadata is read-only");
        }
        orientation = null;
        frame = null;
        exif = null;
        iccProfile = null;
        xmp = null;
        density = null;
    }

    /**
     * Copies the values of another metadata object into this writable one.
     *
     * @param other the metadata to copy
     */
    void copyFrom(SmaltImageMetadata other) {
        orientation = other.writable ? other.orientation : other.orientation();
        frame = other.frame;
        exif = other.exif;
        iccProfile = other.iccProfile;
        xmp = other.xmp;
        density = other.density;
    }

    /**
     * Sets the EXIF block.
     *
     * @param exif the block, a TIFF structure, or {@code null} for none
     */
    void setExif(byte[] exif) {
        this.exif = exif == null ? null : exif.clone();
    }

    /**
     * Sets the ICC profile.
     *
     * @param iccProfile the profile bytes, or {@code null} for none
     */
    void setIccProfile(byte[] iccProfile) {
        this.iccProfile = iccProfile == null ? null : iccProfile.clone();
    }

    /**
     * Sets the XMP packet.
     *
     * @param xmp the packet, or {@code null} for none
     */
    void setXmp(byte[] xmp) {
        this.xmp = xmp == null ? null : xmp.clone();
    }

    /**
     * Sets the orientation.
     *
     * @param orientation the orientation, or {@code null} to leave it to the EXIF block
     */
    void setOrientation(Orientation orientation) {
        this.orientation = orientation;
    }

    /**
     * Sets the frame timing.
     *
     * @param delayMillis the display time in milliseconds, or a negative value to keep the current one
     * @param loopCount the number of plays, 0 for forever, or a negative value to keep the current one
     */
    void setTiming(long delayMillis, int loopCount) {
        var delay = delayMillis >= 0 ? delayMillis : frame == null ? SmaltImageWriter.DEFAULT_DELAY_MILLIS
                : frame.delayMillis();
        var loops = loopCount >= 0 ? loopCount : frame == null ? 0 : frame.loopCount();
        frame = new Frame(frame == null ? 0 : frame.index(), frame == null ? 0 : frame.timestampMillis(), delay, loops);
    }

    /**
     * Merges a native tree.
     *
     * @param root the root node
     * @throws IIOInvalidTreeException if a value is invalid
     */
    private void mergeNative(Node root) throws IIOInvalidTreeException {
        if (!SmaltImageMetadataFormat.NAME.equals(root.getNodeName())) {
            throw new IIOInvalidTreeException("the root must be " + SmaltImageMetadataFormat.NAME, root);
        }
        if (!(root instanceof IIOMetadataNode element)) {
            throw new IIOInvalidTreeException("the root must be an IIOMetadataNode", root);
        }
        if (element.hasAttribute("orientation")) {
            var code = integer(element, "orientation");
            if (code < 1 || code > 8) {
                throw new IIOInvalidTreeException("invalid orientation " + code, root);
            }
            orientation = Orientation.ofExif(code);
        }
        for (var child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!(child instanceof IIOMetadataNode node)) {
                continue;
            }
            switch (node.getNodeName()) {
                case "Exif" -> exif = bytes(node, false);
                case "IccProfile" -> iccProfile = bytes(node, true);
                case "Xmp" -> xmp = bytes(node, false);
                case "Animation" -> setTiming(node.hasAttribute("delayMillis") ? integer(node, "delayMillis") : -1,
                        node.hasAttribute("loopCount") ? integer(node, "loopCount") : -1);
                case "Density" -> density = density(node);
                default -> {
                }
            }
        }
    }

    /**
     * Merges a standard tree, taking its {@code Dimension/ImageOrientation}.
     *
     * @param root the root node
     * @throws IIOInvalidTreeException if the orientation is not a standard name
     */
    private void mergeStandard(Node root) throws IIOInvalidTreeException {
        for (var child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!"Dimension".equals(child.getNodeName())) {
                continue;
            }
            for (var entry = child.getFirstChild(); entry != null; entry = entry.getNextSibling()) {
                if ("ImageOrientation".equals(entry.getNodeName()) && entry instanceof IIOMetadataNode node) {
                    var name = node.getAttribute("value");
                    var code = -1;
                    for (var i = 1; i < STANDARD_ORIENTATIONS.length; i++) {
                        if (STANDARD_ORIENTATIONS[i].equalsIgnoreCase(name)) {
                            code = i;
                        }
                    }
                    if (code < 1) {
                        throw new IIOInvalidTreeException("invalid ImageOrientation " + name, node);
                    }
                    orientation = Orientation.ofExif(code);
                }
            }
        }
    }

    /**
     * Returns the bytes a node carries as its user object.
     *
     * @param node the {@code Exif}, {@code IccProfile} or {@code Xmp} node
     * @param icc whether an {@link ICC_Profile} is accepted
     * @return a copy of the bytes, or {@code null} when the node carries none
     * @throws IIOInvalidTreeException if the user object is of another type
     */
    private static byte[] bytes(IIOMetadataNode node, boolean icc) throws IIOInvalidTreeException {
        return switch (node.getUserObject()) {
            case null -> null;
            case byte[] data -> data.clone();
            case ICC_Profile profile when icc -> profile.getData();
            default -> throw new IIOInvalidTreeException("the user object of " + node.getNodeName()
                    + " must be a byte array", node);
        };
    }

    /**
     * Returns the density a {@code Density} node describes.
     *
     * @param node the {@code Density} node
     * @return the density
     * @throws IIOInvalidTreeException if the unit or a value is invalid
     */
    private static PixelDensity density(IIOMetadataNode node) throws IIOInvalidTreeException {
        try {
            var unit = PixelDensity.Unit.valueOf(node.getAttribute("unit").toUpperCase(Locale.ROOT));
            return new PixelDensity(unit, Double.parseDouble(node.getAttribute("horizontal")),
                    Double.parseDouble(node.getAttribute("vertical")));
        } catch (IllegalArgumentException exception) {
            var failure = new IIOInvalidTreeException("invalid Density", node);
            failure.initCause(exception);
            throw failure;
        }
    }

    /**
     * Reads an integer attribute.
     *
     * @param node the node holding the attribute
     * @param name the attribute
     * @return the value
     * @throws IIOInvalidTreeException if the value is not an integer
     */
    private static int integer(IIOMetadataNode node, String name) throws IIOInvalidTreeException {
        try {
            return Integer.parseInt(node.getAttribute(name));
        } catch (NumberFormatException exception) {
            var failure = new IIOInvalidTreeException("invalid " + name + " " + node.getAttribute(name), node);
            failure.initCause(exception);
            throw failure;
        }
    }

    /**
     * Builds the native tree.
     *
     * @return the root node
     */
    private IIOMetadataNode nativeTree() {
        var root = new IIOMetadataNode(SmaltImageMetadataFormat.NAME);
        root.setAttribute("format", format.name().toLowerCase(Locale.ROOT));
        if (width > 0 && height > 0) {
            root.setAttribute("width", Integer.toString(width));
            root.setAttribute("height", Integer.toString(height));
        }
        root.setAttribute("hasAlpha", Boolean.toString(hasAlpha));
        if (orientation != null) {
            root.setAttribute("orientation", Integer.toString(orientation.exifCode()));
        }
        root.setAttribute("animated", Boolean.toString(frame != null));
        if (frame != null) {
            var animation = new IIOMetadataNode("Animation");
            animation.setAttribute("frameIndex", Integer.toString(frame.index()));
            animation.setAttribute("timestampMillis", Long.toString(frame.timestampMillis()));
            animation.setAttribute("delayMillis", Long.toString(frame.delayMillis()));
            animation.setAttribute("loopCount", Integer.toString(frame.loopCount()));
            root.appendChild(animation);
        }
        if (density != null) {
            var node = new IIOMetadataNode("Density");
            node.setAttribute("unit", density.unit().name().toLowerCase(Locale.ROOT));
            node.setAttribute("horizontal", Double.toString(density.horizontal()));
            node.setAttribute("vertical", Double.toString(density.vertical()));
            root.appendChild(node);
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
        if (xmp != null) {
            var node = new IIOMetadataNode("Xmp");
            node.setUserObject(xmp.clone());
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
        if (density != null) {
            dimension.appendChild(floatNode("PixelAspectRatio", density.pixelAspectRatio()));
        }
        var imageOrientation = new IIOMetadataNode("ImageOrientation");
        imageOrientation.setAttribute("value", STANDARD_ORIENTATIONS[orientation().exifCode()]);
        dimension.appendChild(imageOrientation);
        if (density != null && density.isPhysical()) {
            var perMeter = density.to(PixelDensity.Unit.DOTS_PER_METER);
            dimension.appendChild(floatNode("HorizontalPixelSize", 1000 / perMeter.horizontal()));
            dimension.appendChild(floatNode("VerticalPixelSize", 1000 / perMeter.vertical()));
        }
        return dimension;
    }

    /**
     * Returns a standard-format node whose {@code value} attribute is a float.
     *
     * @param name the node name
     * @param value the number written as the attribute
     * @return the node
     */
    private static IIOMetadataNode floatNode(String name, double value) {
        var node = new IIOMetadataNode(name);
        node.setAttribute("value", Float.toString((float) value));
        return node;
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
