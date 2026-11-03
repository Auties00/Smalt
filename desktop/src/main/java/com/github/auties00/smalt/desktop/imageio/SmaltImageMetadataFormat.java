package com.github.auties00.smalt.desktop.imageio;

import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadataFormat;
import javax.imageio.metadata.IIOMetadataFormatImpl;
import java.util.List;

/**
 * Describes the native image metadata of the Smalt ImageIO readers, {@value #NAME}.
 *
 * <p>The tree is:
 * {@snippet :
 * <com_github_auties00_smalt_image_1.0 format="webp" width="640" height="480" hasAlpha="true"
 *                                       orientation="1" animated="true">
 *   <Animation frameIndex="0" timestampMillis="0" delayMillis="100" loopCount="0"/>
 *   <Density unit="dots_per_inch" horizontal="300.0" vertical="300.0"/>
 *   <Exif/>       <!-- user object: the raw EXIF block, when the image has one -->
 *   <IccProfile/> <!-- user object: the raw ICC profile, when the image has one -->
 * </com_github_auties00_smalt_image_1.0>
 * }
 *
 * <p>{@code orientation} is the EXIF orientation code, {@code 1} to {@code 8}; the readers report
 * it and never apply it. {@code Animation} is present only for animated images, {@code loopCount}
 * counts plays with {@code 0} meaning forever. {@code Density} is present only when the image
 * declares a pixel density, as {@link SmaltImageMetadata#density()} reports it: its {@code unit} is
 * the lowercase name of the {@link com.github.auties00.smalt.image.PixelDensity.Unit} constant and its
 * values are the positive pixels per unit (or, for {@code aspect_ratio}, the relative values).
 */
public final class SmaltImageMetadataFormat extends IIOMetadataFormatImpl {
    /**
     * The name of the format and of its root element.
     */
    public static final String NAME = "com_github_auties00_smalt_image_1.0";

    /**
     * The only instance.
     */
    private static final SmaltImageMetadataFormat INSTANCE = new SmaltImageMetadataFormat();

    /**
     * Declares the elements and attributes of the format.
     */
    private SmaltImageMetadataFormat() {
        super(NAME, CHILD_POLICY_SOME);
        addAttribute(NAME, "format", DATATYPE_STRING, true, null);
        addAttribute(NAME, "width", DATATYPE_INTEGER, true, null, "1", null, true, false);
        addAttribute(NAME, "height", DATATYPE_INTEGER, true, null, "1", null, true, false);
        addBooleanAttribute(NAME, "hasAlpha", false, false);
        addAttribute(NAME, "orientation", DATATYPE_INTEGER, true, "1", "1", "8", true, true);
        addBooleanAttribute(NAME, "animated", false, false);

        addElement("Animation", NAME, CHILD_POLICY_EMPTY);
        addAttribute("Animation", "frameIndex", DATATYPE_INTEGER, true, null, "0", null, true, false);
        addAttribute("Animation", "timestampMillis", DATATYPE_INTEGER, true, null, "0", null, true, false);
        addAttribute("Animation", "delayMillis", DATATYPE_INTEGER, true, null, "0", null, true, false);
        addAttribute("Animation", "loopCount", DATATYPE_INTEGER, true, null, "0", null, true, false);

        addElement("Density", NAME, CHILD_POLICY_EMPTY);
        addAttribute("Density", "unit", DATATYPE_STRING, true, null,
                List.of("aspect_ratio", "dots_per_inch", "dots_per_centimeter", "dots_per_meter"));
        addAttribute("Density", "horizontal", DATATYPE_DOUBLE, true, null, "0", null, false, false);
        addAttribute("Density", "vertical", DATATYPE_DOUBLE, true, null, "0", null, false, false);

        addElement("Exif", NAME, CHILD_POLICY_EMPTY);
        addObjectValue("Exif", byte.class, 0, Integer.MAX_VALUE);

        addElement("IccProfile", NAME, CHILD_POLICY_EMPTY);
        addObjectValue("IccProfile", byte.class, 0, Integer.MAX_VALUE);
    }

    /**
     * Returns the only instance, as {@link javax.imageio.metadata.IIOMetadata#getMetadataFormat(String)}
     * requires.
     *
     * @return the format
     */
    public static IIOMetadataFormat getInstance() {
        return INSTANCE;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every element may appear in metadata of any image type.
     */
    @Override
    public boolean canNodeAppear(String elementName, ImageTypeSpecifier imageType) {
        return List.of(NAME, "Animation", "Density", "Exif", "IccProfile").contains(elementName);
    }
}
