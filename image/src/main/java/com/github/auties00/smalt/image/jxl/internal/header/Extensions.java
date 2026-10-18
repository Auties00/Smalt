/* Ports jxl-oxide 0.12.6 (crates/jxl-bitstream: Extensions). Copyright Wonwoo Choi and the jxl-oxide contributors;
   MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.header;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlBitReader;

/**
 * Skips the {@code Extensions} bundle of a JPEG XL header (ISO/IEC 18181-1 section A.5): a mask of
 * up to 64 extensions, then the bit length of each present one, then their payloads.
 *
 * <p>No extension is defined yet, so every payload is skipped.
 */
public final class Extensions {
    /**
     * Prevents instantiation.
     */
    private Extensions() {
        throw new AssertionError();
    }

    /**
     * Skips an extensions bundle.
     *
     * @param br the bitstream
     * @throws MediaException.Malformed if the payload lengths exceed the bitstream
     */
    public static void skip(JxlBitReader br) {
        var mask = br.readU64();
        var total = 0L;
        for (var i = 0; i < 64; i++) {
            if ((mask & (1L << i)) != 0) {
                var length = br.readU64();
                if (length < 0 || length > br.remainingBits() || total + length > br.remainingBits()) {
                    throw new MediaException.Malformed("JPEG XL extension longer than the codestream");
                }
                total += length;
            }
        }
        br.skipBits(total);
    }
}
