package com.github.auties00.smalt.image.tiff.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.internal.jbig.JbigDecoder;

import java.util.zip.DataFormatException;

/**
 * Decodes the JBIG strips and tiles of TIFF compression 34661, each a self-contained JBIG bi-level image
 * entity as wide as the strip or tile; of an entity with several bit-planes, the most significant one
 * gives the page.
 *
 * <p>Instances are not thread-safe.
 */
final class TiffJbig {
    /** The decoder. */
    private final JbigDecoder decoder = new JbigDecoder();

    /** The limits. */
    private final MediaLimits limits;

    /**
     * Constructs a decoder.
     *
     * @param limits the limits the decoded planes must fit
     */
    TiffJbig(MediaLimits limits) {
        this.limits = limits;
    }

    /**
     * Decodes one strip or tile.
     *
     * @param raw the stored bytes
     * @param length the number of stored bytes
     * @param chunk receives the bilevel rows, most significant bit first
     * @param rowBytes the bytes of a row
     * @param columns the pixels of a row
     * @param rows the rows of the strip or tile
     * @return the bytes of the rows the entity holds, at most {@code rows * rowBytes}
     * @throws MediaException.Malformed if the data is not a JBIG entity as wide as the strip or tile
     */
    int decode(byte[] raw, int length, byte[] chunk, int rowBytes, int columns, int rows) {
        JbigDecoder.Image image;
        try {
            // an entity much taller than its strip is damaged; twice the strip bounds the work it can cause
            image = decoder.decode(raw, 0, length, Math.min(limits.maxPixels(), (long) columns * rows * 2 + columns),
                    true);
        } catch (DataFormatException exception) {
            throw new MediaException.Malformed("invalid JBIG data in a TIFF strip or tile: " + exception.getMessage(),
                    exception);
        }
        if (image.width() != columns) {
            throw new MediaException.Malformed("JBIG image " + image.width() + " pixels wide in a TIFF strip or tile of "
                    + columns);
        }
        var lines = Math.min(rows, image.height());
        System.arraycopy(image.planes()[0], 0, chunk, 0, lines * rowBytes);
        return lines * rowBytes;
    }
}
