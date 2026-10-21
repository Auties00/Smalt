package com.github.auties00.smalt.image.png;

/**
 * Defines the datastream signature and the chunk type codes shared by the PNG reader and writer.
 *
 * <p>A chunk type is four ASCII letters; this class packs it big-endian into an {@code int}, so
 * {@code IHDR} is {@code 0x49484452}. Bit 5 of the first letter (lowercase) marks an ancillary
 * chunk, which a decoder may ignore; an uppercase first letter marks a critical chunk, which a
 * decoder must understand.
 */
final class PngChunks {
    /**
     * The eight bytes every PNG datastream starts with (PNG 3rd edition, 5.2).
     */
    static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    /**
     * The image header chunk type.
     */
    static final int IHDR = 0x49484452;

    /**
     * The palette chunk type.
     */
    static final int PLTE = 0x504C5445;

    /**
     * The image data chunk type.
     */
    static final int IDAT = 0x49444154;

    /**
     * The image trailer chunk type.
     */
    static final int IEND = 0x49454E44;

    /**
     * The transparency chunk type.
     */
    static final int TRNS = 0x74524E53;

    /**
     * The embedded ICC profile chunk type.
     */
    static final int ICCP = 0x69434350;

    /**
     * The image gamma chunk type.
     */
    static final int GAMA = 0x67414D41;

    /**
     * The primary chromaticities chunk type.
     */
    static final int CHRM = 0x6348524D;

    /**
     * The standard RGB color space chunk type.
     */
    static final int SRGB = 0x73524742;

    /**
     * The coding-independent code points chunk type.
     */
    static final int CICP = 0x63494350;

    /**
     * The mastering display color volume chunk type, {@code mDCV}.
     */
    static final int MDCV = 0x6D444356;

    /**
     * The draft spelling of the mastering display color volume chunk type, {@code mDCv}, which
     * readers accept.
     */
    static final int MDCV_DRAFT = 0x6D444376;

    /**
     * The content light level information chunk type, {@code cLLI}.
     */
    static final int CLLI = 0x634C4C49;

    /**
     * The draft spelling of the content light level information chunk type, {@code cLLi}, which
     * readers accept.
     */
    static final int CLLI_DRAFT = 0x634C4C69;

    /**
     * The significant bits chunk type.
     */
    static final int SBIT = 0x73424954;

    /**
     * The physical pixel dimensions chunk type.
     */
    static final int PHYS = 0x70485973;

    /**
     * The Exif profile chunk type.
     */
    static final int EXIF = 0x65584966;

    /**
     * The international textual data chunk type, which carries the XMP packet.
     */
    static final int ITXT = 0x69545874;

    /**
     * The animation control chunk type.
     */
    static final int ACTL = 0x6163544C;

    /**
     * The frame control chunk type.
     */
    static final int FCTL = 0x6663544C;

    /**
     * The frame data chunk type.
     */
    static final int FDAT = 0x66644154;

    /**
     * The largest chunk length the specification allows, {@code 2^31 - 1}.
     */
    static final long MAX_CHUNK_LENGTH = 0x7FFF_FFFFL;

    /**
     * Prevents instantiation.
     */
    private PngChunks() {
        throw new AssertionError();
    }

    /**
     * Returns whether a chunk type is critical.
     *
     * @param type the packed chunk type
     * @return {@code true} when the first letter is uppercase
     */
    static boolean isCritical(int type) {
        return (type & 0x2000_0000) == 0;
    }

    /**
     * Returns whether every byte of a chunk type is an ASCII letter, as PNG 3rd edition 5.4
     * requires.
     *
     * @param type the packed chunk type
     * @return {@code true} for a well-formed type code
     */
    static boolean isValidType(int type) {
        for (var shift = 24; shift >= 0; shift -= 8) {
            var letter = (type >>> shift) & 0xFF;
            if ((letter < 'A' || letter > 'Z') && (letter < 'a' || letter > 'z')) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the printable name of a chunk type.
     *
     * @param type the packed chunk type
     * @return the four letters, with non-printable bytes shown as {@code ?}
     */
    static String name(int type) {
        var builder = new StringBuilder(4);
        for (var shift = 24; shift >= 0; shift -= 8) {
            var letter = (type >>> shift) & 0xFF;
            builder.append(letter >= 0x20 && letter < 0x7F ? (char) letter : '?');
        }
        return builder.toString();
    }
}
