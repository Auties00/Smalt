package com.github.auties00.smalt.probe;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.SourceReader;
import com.github.auties00.smalt.internal.tiff.TiffExif;
import com.github.auties00.smalt.io.MediaSource;

import java.util.Arrays;

/**
 * Describes a JPEG 2000 file from its boxes and the codestream's {@code SIZ} segment (ISO/IEC
 * 15444-1 annex A and annex I), without decoding packets.
 *
 * <p>The size and bit depth come from {@code SIZ} (the largest component precision); alpha from a
 * channel definition of an opacity channel, or, without channel definitions, from a channel
 * count beyond the color space's (two channels with one color, four with three); the
 * orientation from the EXIF block of a {@code uuid} box.
 */
final class Jp2Headers {
    /**
     * The largest number of boxes examined.
     */
    private static final int MAX_BOXES = 1 << 12;

    /**
     * The largest EXIF block read.
     */
    private static final int MAX_EXIF = 1 << 20;

    /**
     * The UUID of the box that holds an EXIF block, {@code JpgTiffExif->JP2}.
     */
    private static final byte[] EXIF_UUID = {'J', 'p', 'g', 'T', 'i', 'f', 'f', 'E', 'x', 'i', 'f', '-', '>', 'J', 'P', '2'};

    /**
     * Prevents instantiation.
     */
    private Jp2Headers() {
        throw new AssertionError();
    }

    /**
     * Describes a JPEG 2000 file or raw codestream.
     *
     * @param source the input
     * @return the header
     * @throws MediaException.Malformed if the headers are corrupt or truncated
     * @throws MediaException.Io if reading the source fails
     */
    static ImageHeaders.Header read(MediaSource source) {
        var result = new ImageHeaders.Header();
        var reader = new SourceReader(source, 8192);
        if (reader.remaining() >= 4 && reader.u16be() == 0xFF4F) {
            reader.seek(0);
            readSiz(reader, result);
            result.alpha = false;
            return result;
        }
        reader.seek(12);
        var channels = -1;
        var colorChannels = 3;
        var definedAlpha = -1;
        var codestream = -1L;
        for (var step = 0; step < MAX_BOXES && reader.remaining() >= 8 && codestream < 0; step++) {
            var start = reader.position();
            var length = reader.u32be();
            var type = reader.fourCc();
            var header = 8L;
            if (length == 1) {
                length = reader.u64be();
                header = 16;
            } else if (length == 0) {
                length = reader.size() - start;
            }
            if (length < header) {
                throw new MediaException.Malformed("invalid JPEG 2000 box length " + length);
            }
            switch (type) {
                case 0x6A703268 -> {
                    var end = start + length;
                    while (reader.position() + 8 <= end) {
                        var inner = reader.position();
                        var innerLength = reader.u32be();
                        var innerType = reader.fourCc();
                        if (innerLength < 8) {
                            break;
                        }
                        switch (innerType) {
                            case 0x69686472 -> {
                                reader.skip(8);
                                channels = reader.u16be();
                            }
                            case 0x636F6C72 -> {
                                var method = reader.u8();
                                reader.skip(2);
                                if (method == 1 && innerLength >= 15) {
                                    var space = reader.u32be();
                                    colorChannels = space == 17 || space == 0 || space == 15 ? 1 : space == 12 ? 4 : 3;
                                }
                            }
                            case 0x636D6170 -> channels = (int) ((innerLength - 8) / 4);
                            case 0x63646566 -> {
                                var count = reader.u16be();
                                definedAlpha = 0;
                                for (var i = 0; i < count && reader.position() + 6 <= inner + innerLength; i++) {
                                    reader.skip(2);
                                    var channelType = reader.u16be();
                                    reader.skip(2);
                                    if (channelType == 1 || channelType == 2) {
                                        definedAlpha = 1;
                                    }
                                }
                            }
                            default -> {
                            }
                        }
                        reader.seek(inner + innerLength);
                    }
                }
                case 0x75756964 -> {
                    if (length - header >= 24 && length - header <= MAX_EXIF) {
                        var body = reader.bytes(length - header, MAX_EXIF);
                        if (Arrays.equals(body, 0, 16, EXIF_UUID, 0, 16)) {
                            var offset = EXIF_UUID.length;
                            result.orientation = TiffExif.orientation(body, offset + TiffExif.prefixLength(body, offset));
                        }
                    }
                }
                case 0x6A703263 -> codestream = start + header;
                default -> {
                }
            }
            if (codestream < 0) {
                reader.seek(start + length);
            }
        }
        if (codestream < 0) {
            throw new MediaException.Malformed("the JPEG 2000 file has no codestream box");
        }
        reader.seek(codestream);
        readSiz(reader, result);
        if (definedAlpha >= 0) {
            result.alpha = definedAlpha == 1;
        } else if (channels > 0) {
            var colors = Math.min(colorChannels, channels);
            result.alpha = channels > colors && (channels == 2 || channels == 4) && colorChannels != 4;
        }
        return result;
    }

    /**
     * Reads the {@code SIZ} segment that follows the {@code SOC} marker.
     *
     * @param reader the reader, at the {@code SOC} marker
     * @param result the header receiving the size and bit depth
     * @throws MediaException.Malformed if the markers are invalid
     */
    private static void readSiz(SourceReader reader, ImageHeaders.Header result) {
        if (reader.u16be() != 0xFF4F || reader.u16be() != 0xFF51) {
            throw new MediaException.Malformed("missing JPEG 2000 SIZ segment");
        }
        reader.skip(4);
        var x1 = reader.u32be();
        var y1 = reader.u32be();
        var x0 = reader.u32be();
        var y0 = reader.u32be();
        reader.skip(16);
        var components = reader.u16be();
        if (x1 <= x0 || y1 <= y0 || components == 0) {
            throw new MediaException.Malformed("invalid JPEG 2000 SIZ segment");
        }
        result.width = (int) Math.min(Integer.MAX_VALUE, x1 - x0);
        result.height = (int) Math.min(Integer.MAX_VALUE, y1 - y0);
        var depth = 0;
        for (var i = 0; i < Math.min(components, 16384); i++) {
            depth = Math.max(depth, (reader.u8() & 0x7F) + 1);
            reader.skip(2);
        }
        result.bitDepth = depth;
    }
}
