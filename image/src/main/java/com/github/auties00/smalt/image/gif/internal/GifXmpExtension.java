package com.github.auties00.smalt.image.gif.internal;

import java.nio.charset.StandardCharsets;

/**
 * Builds the {@code XMP DataXMP} application extension that embeds an XMP packet in a GIF datastream (XMP
 * specification part 3, 1.1.2).
 *
 * <p>The extension is the extension introducer {@code 21}, the application label {@code FF}, an 11-byte
 * block holding the identifier {@code XMP Data} and the authentication code {@code XMP}, then the packet
 * bytes as they are (the packet holds no zero byte, so a GIF reader skipping the extension reads its bytes
 * as sub-block lengths), the 258-byte magic trailer {@code 01 FF FE ... 01 00} that brings such a reader to
 * the block terminator, and the terminator.
 */
public final class GifXmpExtension {
    /**
     * The application identifier and authentication code.
     */
    private static final byte[] IDENTIFIER = "XMP DataXMP".getBytes(StandardCharsets.US_ASCII);

    /**
     * Prevents instantiation.
     */
    private GifXmpExtension() {
        throw new AssertionError();
    }

    /**
     * Appends the extension that embeds a packet.
     *
     * @param out the datastream
     * @param xmp the XMP packet in UTF-8, holding no zero byte
     */
    public static void write(GifOutput out, byte[] xmp) {
        out.write(0x21);
        out.write(0xFF);
        out.write(IDENTIFIER.length);
        out.write(IDENTIFIER, 0, IDENTIFIER.length);
        out.write(xmp, 0, xmp.length);
        out.write(0x01);
        for (var value = 0xFF; value >= 0; value--) {
            out.write(value);
        }
        out.write(0);
    }
}
