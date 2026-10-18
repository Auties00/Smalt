package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.exif.ExifReader;
import com.github.auties00.smalt.internal.io.SourceReader;
import com.github.auties00.smalt.io.MediaSource;

/**
 * Reads the size and orientation of a JPEG image from its headers, without loading the whole
 * input.
 *
 * <p>The probe walks the marker segments from SOI, reading only the SOF segment and the first
 * {@code APP1 Exif} segment and skipping every other segment by seeking. It stops at the first SOS
 * marker, except when the frame height is zero, in which case it scans the first scan's entropy
 * data for the DNL segment that defines the height. The probe accepts every frame type, including
 * those the decoder rejects as unsupported, since their headers are well defined.
 */
public final class JpegProbe {
    /**
     * Prevents instantiation.
     */
    private JpegProbe() {
        throw new AssertionError();
    }

    /**
     * Probes a JPEG image.
     *
     * @param source the input
     * @return the summary, with the orientation of the first Exif segment
     * @throws MediaException.Malformed if the input is not a JPEG stream with a valid frame header
     * @throws MediaException.Io if reading the source fails
     */
    public static ImageInfo probe(MediaSource source) {
        var reader = new SourceReader(source);
        if (reader.u8() != 0xFF || reader.u8() != 0xD8) {
            throw new MediaException.Malformed("missing JPEG SOI marker");
        }
        var width = -1;
        var height = -1;
        var orientation = Orientation.NORMAL;
        var sawExif = false;
        while (true) {
            if (reader.atEnd()) {
                break;
            }
            var marker = nextMarker(reader);
            if (marker < 0) {
                break;
            }
            if (marker == 0xD9) {
                break;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                continue;
            }
            if (marker == 0xDA) {
                if (width < 0) {
                    throw new MediaException.Malformed("JPEG scan before the frame header");
                }
                if (height == 0) {
                    height = findDnl(reader);
                }
                break;
            }
            if (marker < 0xC0) {
                throw new MediaException.Malformed("unknown JPEG marker 0x" + Integer.toHexString(marker));
            }
            var length = reader.u16be();
            if (length < 2) {
                throw new MediaException.Malformed("JPEG segment length " + length + " below 2");
            }
            var payload = length - 2;
            var sof = (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC)
                    || marker == 0xF7;
            if (sof && width < 0) {
                if (payload < 6) {
                    throw new MediaException.Malformed("truncated SOF segment");
                }
                reader.u8();
                height = reader.u16be();
                width = reader.u16be();
                var components = reader.u8();
                if (width == 0 || components == 0) {
                    throw new MediaException.Malformed("empty JPEG frame");
                }
                reader.skip(payload - 6);
            } else if (marker == 0xE1 && !sawExif && payload >= 6) {
                var signature = new byte[JpegExif.PREFIX];
                reader.readFully(signature, 0, JpegExif.PREFIX);
                if (JpegExif.prefixLength(signature, 0, JpegExif.PREFIX) != 0) {
                    var tiff = new byte[payload - JpegExif.PREFIX];
                    reader.readFully(tiff, 0, tiff.length);
                    orientation = ExifReader.orientation(tiff);
                    sawExif = true;
                } else {
                    reader.skip(payload - JpegExif.PREFIX);
                }
            } else {
                reader.skip(payload);
            }
        }
        if (width < 0) {
            throw new MediaException.Malformed("JPEG stream has no frame header");
        }
        if (height <= 0) {
            throw new MediaException.Malformed("JPEG frame height is zero and no DNL segment defines it");
        }
        return new ImageInfo(ImageFormat.JPEG, width, height, false, false, 1, orientation);
    }

    /**
     * Finds the next marker, skipping bytes that are not part of one.
     *
     * @param reader the input cursor
     * @return the marker code, or {@code -1} at the end of the input
     */
    private static int nextMarker(SourceReader reader) {
        while (!reader.atEnd()) {
            if (reader.u8() != 0xFF) {
                continue;
            }
            var code = 0xFF;
            while (code == 0xFF) {
                if (reader.atEnd()) {
                    return -1;
                }
                code = reader.u8();
            }
            if (code != 0) {
                return code;
            }
        }
        return -1;
    }

    /**
     * Skips the rest of an SOS segment and its entropy-coded data, and reads the DNL segment that
     * must follow.
     *
     * @param reader the input cursor, just after the SOS marker code
     * @return the number of lines the DNL segment defines
     * @throws MediaException.Malformed if the scan is not followed by a valid DNL segment
     */
    private static int findDnl(SourceReader reader) {
        var length = reader.u16be();
        if (length < 2) {
            throw new MediaException.Malformed("JPEG segment length " + length + " below 2");
        }
        reader.skip(length - 2);
        while (true) {
            var marker = nextMarker(reader);
            if (marker < 0) {
                break;
            }
            if (marker < 0xC0 || (marker >= 0xD0 && marker <= 0xD7)) {
                continue;
            }
            if (marker == 0xDC) {
                var dnlLength = reader.u16be();
                var lines = reader.u16be();
                if (dnlLength == 4 && lines > 0) {
                    return lines;
                }
            }
            break;
        }
        throw new MediaException.Malformed("JPEG frame height is zero and no DNL segment defines it");
    }
}
