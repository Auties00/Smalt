package com.github.auties00.smalt.image.webp.internal.riff;

/**
 * Names the RIFF chunk tags of the WebP container as big-endian 32-bit values, the form
 * {@link com.github.auties00.smalt.internal.io.SourceReader#fourCc()} returns.
 */
public final class FourCc {
    /**
     * The RIFF container tag.
     */
    public static final int RIFF = of("RIFF");

    /**
     * The WebP form type.
     */
    public static final int WEBP = of("WEBP");

    /**
     * The extended-format header chunk.
     */
    public static final int VP8X = of("VP8X");

    /**
     * The lossy bitstream chunk.
     */
    public static final int VP8 = of("VP8 ");

    /**
     * The lossless bitstream chunk.
     */
    public static final int VP8L = of("VP8L");

    /**
     * The alpha chunk of a lossy image.
     */
    public static final int ALPH = of("ALPH");

    /**
     * The animation parameters chunk.
     */
    public static final int ANIM = of("ANIM");

    /**
     * The animation frame chunk.
     */
    public static final int ANMF = of("ANMF");

    /**
     * The ICC profile chunk.
     */
    public static final int ICCP = of("ICCP");

    /**
     * The EXIF metadata chunk.
     */
    public static final int EXIF = of("EXIF");

    /**
     * The XMP metadata chunk.
     */
    public static final int XMP = of("XMP ");

    /**
     * Prevents instantiation.
     */
    private FourCc() {
        throw new AssertionError();
    }

    /**
     * Packs a four-character tag.
     *
     * @param tag the tag, four ASCII characters
     * @return the big-endian packed value
     */
    public static int of(String tag) {
        return tag.charAt(0) << 24 | tag.charAt(1) << 16 | tag.charAt(2) << 8 | tag.charAt(3);
    }

    /**
     * Returns the text of a packed tag, replacing non-printable bytes with {@code '?'}.
     *
     * @param fourCc the packed tag
     * @return the four characters
     */
    public static String toString(int fourCc) {
        var chars = new char[4];
        for (var i = 0; i < 4; i++) {
            var c = (fourCc >>> (24 - 8 * i)) & 0xFF;
            chars[i] = c >= 0x20 && c < 0x7F ? (char) c : '?';
        }
        return new String(chars);
    }
}
