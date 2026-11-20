/* Ports libjxl 0.11.2 (lib/extras/gain_map.cc: JxlGainMapReadBundle). Copyright the JPEG XL Project Authors;
   BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.bitstream;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.jxl.internal.color.IccStream;
import com.github.auties00.smalt.image.jxl.internal.header.ColorEncoding;

import java.util.Arrays;

/**
 * Holds the content of a {@code jhgm} (gain map) box.
 *
 * <p>The box stores, in order: a one-byte version; the size of the gain map metadata as a big-endian 16-bit
 * integer, then the metadata; the size of the color encoding of the alternate rendition as one byte, then that
 * many bytes of a {@code ColorEncoding} bundle (none when the size is 0); the size of the ICC profile of the
 * alternate rendition as a big-endian 32-bit integer, then the profile compressed as a codestream ICC stream
 * (none when the size is 0); and, to the end of the box, the gain map as a bare codestream.
 *
 * @param version the box version, from 0 to 255
 * @param metadata the gain map metadata, ISO 21496-1 binary metadata
 * @param colorEncoding the color encoding of the alternate rendition, or {@code null} when the box stores none
 * @param icc the decompressed ICC profile of the alternate rendition, or {@code null} when the box stores none
 * @param codestream the gain map codestream
 */
public record GainMapBundle(int version, byte[] metadata, ColorEncoding colorEncoding, byte[] icc,
                            byte[] codestream) {
    /**
     * Parses the content of a {@code jhgm} box.
     *
     * @param data the box content, decompressed when it was stored in a {@code brob} box
     * @param limits the resource bounds, whose byte limit caps the decompressed ICC profile
     * @return the parsed content
     * @throws MediaException.Malformed if a size runs past the box, the color encoding or the ICC stream is
     * invalid, or the gain map codestream is empty
     * @throws MediaException.LimitExceeded if the ICC profile exceeds the byte limit
     */
    public static GainMapBundle parse(byte[] data, MediaLimits limits) {
        var cursor = 0;
        if (data.length < 3) {
            throw new MediaException.Malformed("truncated jhgm box");
        }
        var version = data[cursor++] & 0xFF;
        var metadataSize = ((data[cursor] & 0xFF) << 8) | (data[cursor + 1] & 0xFF);
        cursor += 2;
        if (data.length - cursor < metadataSize + 1) {
            throw new MediaException.Malformed("truncated jhgm metadata");
        }
        var metadata = Arrays.copyOfRange(data, cursor, cursor + metadataSize);
        cursor += metadataSize;
        var colorSize = data[cursor++] & 0xFF;
        ColorEncoding colorEncoding = null;
        if (colorSize > 0) {
            if (data.length - cursor < colorSize) {
                throw new MediaException.Malformed("truncated jhgm color encoding");
            }
            colorEncoding = ColorEncoding.read(new JxlBitReader(data, cursor, colorSize));
            cursor += colorSize;
        }
        if (data.length - cursor < 4) {
            throw new MediaException.Malformed("truncated jhgm ICC size");
        }
        var iccSize = ((long) (data[cursor] & 0xFF) << 24) | ((data[cursor + 1] & 0xFF) << 16)
                | ((data[cursor + 2] & 0xFF) << 8) | (data[cursor + 3] & 0xFF);
        cursor += 4;
        if (data.length - cursor < iccSize) {
            throw new MediaException.Malformed("truncated jhgm ICC profile");
        }
        byte[] icc = null;
        if (iccSize > 0) {
            icc = IccStream.read(new JxlBitReader(data, cursor, (int) iccSize), limits.maxBytes());
            cursor += (int) iccSize;
        }
        if (cursor == data.length) {
            throw new MediaException.Malformed("jhgm box without a gain map codestream");
        }
        return new GainMapBundle(version, metadata, colorEncoding, icc, Arrays.copyOfRange(data, cursor, data.length));
    }
}
