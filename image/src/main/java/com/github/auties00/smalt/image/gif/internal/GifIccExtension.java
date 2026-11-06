package com.github.auties00.smalt.image.gif.internal;

import java.nio.charset.StandardCharsets;

/**
 * Builds the {@code ICCRGBG1} application extension that embeds an ICC profile in a GIF datastream
 * (ICC.1 Annex B).
 *
 * <p>The extension is the extension introducer {@code 21}, the application label {@code FF}, an
 * 11-byte block holding the identifier {@code ICCRGBG1} and the authentication code {@code 012},
 * then the profile split into data sub-blocks of at most 255 bytes and the block terminator.
 */
public final class GifIccExtension {
    /**
     * The application identifier and authentication code.
     */
    private static final byte[] IDENTIFIER = "ICCRGBG1012".getBytes(StandardCharsets.US_ASCII);

    /**
     * Prevents instantiation.
     */
    private GifIccExtension() {
        throw new AssertionError();
    }

    /**
     * Returns the size of the extension that embeds a profile.
     *
     * @param profileLength the profile size in bytes
     * @return the extension size in bytes
     */
    public static long size(long profileLength) {
        return 3 + IDENTIFIER.length + profileLength + (profileLength + 254) / 255 + 1;
    }

    /**
     * Appends the extension that embeds a profile.
     *
     * @param out the datastream
     * @param profile the ICC profile bytes
     */
    public static void write(GifOutput out, byte[] profile) {
        out.write(0x21);
        out.write(0xFF);
        out.write(IDENTIFIER.length);
        out.write(IDENTIFIER, 0, IDENTIFIER.length);
        for (var offset = 0; offset < profile.length; offset += 255) {
            var length = Math.min(255, profile.length - offset);
            out.write(length);
            out.write(profile, offset, length);
        }
        out.write(0);
    }

    /**
     * Returns the extension that embeds a profile.
     *
     * @param profile the ICC profile bytes
     * @return the extension bytes
     */
    public static byte[] of(byte[] profile) {
        var out = new GifOutput((int) Math.min(Integer.MAX_VALUE - 16, size(profile.length)));
        write(out, profile);
        return out.toByteArray();
    }
}
