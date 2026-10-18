package com.github.auties00.smalt.image.color;

/**
 * Identifies the device class of a {@link ColorProfile} (ICC.1:2022 section 7.2.5).
 *
 * <p>Only input, display, output and color space profiles convert between device values and the
 * connection space, so only they can be the source or the target of a {@link ColorTransform}.
 * Constants may be added in minor releases; a {@code switch} over them needs a default.
 */
public enum ProfileClass {
    /**
     * An input device such as a camera or a scanner ({@code scnr}).
     */
    INPUT,

    /**
     * A display ({@code mntr}); every matrix/TRC working space such as sRGB uses this class.
     */
    DISPLAY,

    /**
     * An output device such as a printer ({@code prtr}).
     */
    OUTPUT,

    /**
     * A device link, which connects two device spaces directly ({@code link}).
     */
    DEVICE_LINK,

    /**
     * A color space conversion ({@code spac}).
     */
    COLOR_SPACE,

    /**
     * An abstract transform within the connection space ({@code abst}).
     */
    ABSTRACT,

    /**
     * A list of named colors ({@code nmcl}).
     */
    NAMED_COLOR,

    /**
     * A class this version does not know.
     */
    OTHER;

    /**
     * Returns the class of an ICC signature.
     *
     * @param signature the device class signature
     * @return the constant, {@link #OTHER} for an unknown signature
     */
    static ProfileClass of(int signature) {
        return switch (IccReader.name(signature)) {
            case "scnr" -> INPUT;
            case "mntr" -> DISPLAY;
            case "prtr" -> OUTPUT;
            case "link" -> DEVICE_LINK;
            case "spac" -> COLOR_SPACE;
            case "abst" -> ABSTRACT;
            case "nmcl" -> NAMED_COLOR;
            default -> OTHER;
        };
    }
}
