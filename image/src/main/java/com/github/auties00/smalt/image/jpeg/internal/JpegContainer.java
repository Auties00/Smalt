package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.metadata.XmpPacket;
import com.github.auties00.smalt.metadata.XmpValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the images a JPEG file holds after its primary image: the Multi-Picture Format index (CIPA DC-007) of
 * its {@code APP2 MPF} segment, and the container directory of its XMP packet (the Google container items of
 * Ultra HDR and Dynamic Depth files), with the marker segments of each image's header.
 *
 * <p>This class is thread-safe.
 */
public final class JpegContainer {
    /**
     * The identifier of the Multi-Picture Format {@code APP2} segment.
     */
    public static final byte[] MPF_ID = {'M', 'P', 'F', 0};

    /**
     * The identifier of the ISO 21496-1 gain map metadata {@code APP2} segment.
     */
    public static final byte[] ISO_ID = "urn:iso:std:iso:ts:21496:-1\0".getBytes(StandardCharsets.ISO_8859_1);

    /**
     * The XMP namespace of the container directory.
     */
    public static final String CONTAINER = "http://ns.google.com/photos/1.0/container/";

    /**
     * The XMP namespace of the container items.
     */
    public static final String ITEM = "http://ns.google.com/photos/1.0/container/item/";

    /**
     * The tag of the MP entries.
     */
    private static final int MP_ENTRY = 0xB002;

    /**
     * The most images an index lists.
     */
    private static final int MAX_IMAGES = 256;

    private JpegContainer() {
        throw new AssertionError();
    }

    /**
     * A marker segment of an image header.
     *
     * @param marker the marker code, from {@code 0xC0} to {@code 0xFE}
     * @param offset the index of the payload, after the length field
     * @param length the payload length
     */
    public record Segment(int marker, int offset, int length) {
        /**
         * {@return whether the payload starts with an identifier}
         *
         * @param data the file
         * @param id the identifier
         */
        public boolean startsWith(byte[] data, byte[] id) {
            return JpegXmp.startsWith(data, offset, length, id);
        }
    }

    /**
     * An image of the file.
     *
     * @param offset the index of its SOI marker
     * @param length its length in bytes, through the end of the file for the last image
     * @param type the Multi-Picture Format type code, or {@code -1} when the file has no index
     * @param semantic the {@code Item:Semantic} of its container directory entry, or {@code null}
     */
    public record Image(int offset, int length, int type, String semantic) {
    }

    /**
     * Reads the marker segments before the first scan of an image.
     *
     * @param data the file
     * @param offset the index of the image's SOI marker
     * @param end the index after the last byte of the image
     * @return the segments, in order; the segments read before a damaged one when the header is damaged
     * @throws MediaException.Malformed if the image does not start with an SOI marker
     */
    public static List<Segment> headers(byte[] data, int offset, int end) {
        if (end - offset < 2 || (data[offset] & 0xFF) != 0xFF || (data[offset + 1] & 0xFF) != 0xD8) {
            throw new MediaException.Malformed("missing JPEG SOI marker");
        }
        var segments = new ArrayList<Segment>();
        var at = offset + 2;
        while (at + 4 <= end) {
            if ((data[at] & 0xFF) != 0xFF) {
                break;
            }
            var marker = data[at + 1] & 0xFF;
            if (marker == 0xFF) {
                at++;
                continue;
            }
            if (marker == 0xD9 || marker == 0xDA || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) {
                break;
            }
            var length = ByteArrays.u16(data, at + 2, false);
            if (length < 2 || at + 2 + length > end) {
                break;
            }
            segments.add(new Segment(marker, at + 4, length - 2));
            at += 2 + length;
        }
        return segments;
    }

    /**
     * Finds the end of an image: the index after its EOI marker.
     *
     * @param data the file
     * @param offset the index of the image's SOI marker
     * @return the index after the EOI marker, or the length of the file when the image has none
     */
    public static int imageEnd(byte[] data, int offset) {
        var end = data.length;
        var at = offset + 2;
        while (at + 2 <= end) {
            if ((data[at] & 0xFF) != 0xFF) {
                at++;
                continue;
            }
            var marker = data[at + 1] & 0xFF;
            if (marker == 0xFF) {
                at++;
                continue;
            }
            if (marker == 0xD9) {
                return at + 2;
            }
            if (marker == 0x00 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) {
                at += 2;
                continue;
            }
            if (at + 4 > end) {
                return end;
            }
            var length = ByteArrays.u16(data, at + 2, false);
            at += 2 + Math.max(length, 2);
            if (marker == 0xDA) {
                while (at + 1 < end) {
                    if ((data[at] & 0xFF) == 0xFF) {
                        var next = data[at + 1] & 0xFF;
                        if (next != 0x00 && !(next >= 0xD0 && next <= 0xD7) && next != 0xFF) {
                            break;
                        }
                    }
                    at++;
                }
            }
        }
        return end;
    }

    /**
     * Lists the images of a file: the primary image first, then those its Multi-Picture Format index lists, or
     * without an index those its XMP container directory lists after the primary image.
     *
     * <p>An index entry that points outside the file or not at an SOI marker is left out, as are the entries
     * beyond {@value #MAX_IMAGES}.
     *
     * @param data the file
     * @param xmp the XMP packet of the primary image, or {@code null}
     * @return the images
     * @throws MediaException.Malformed if the file does not start with an SOI marker
     */
    public static List<Image> images(byte[] data, XmpPacket xmp) {
        var headers = headers(data, 0, data.length);
        var semantics = semantics(xmp);
        var images = new ArrayList<Image>();
        for (var segment : headers) {
            if (segment.marker() == 0xE2 && segment.startsWith(data, MPF_ID)) {
                index(data, segment, semantics, images);
                break;
            }
        }
        if (images.isEmpty()) {
            images.add(new Image(0, imageEnd(data, 0), -1, semantics.isEmpty() ? null : semantics.getFirst()[0]));
            if (semantics.size() > 1) {
                var at = images.getFirst().length();
                for (var item = 1; item < semantics.size() && at < data.length; item++) {
                    var entry = semantics.get(item);
                    at += parseLength(entry[2]);
                    var length = parseLength(entry[1]);
                    if (length <= 0 || at + (long) length > data.length || !isSoi(data, at)) {
                        break;
                    }
                    images.add(new Image(at, length, -1, entry[0]));
                    at += length;
                }
            }
        }
        return images;
    }

    /**
     * Reads the images of a Multi-Picture Format index.
     *
     * @param data the file
     * @param segment the {@code APP2 MPF} segment
     * @param semantics the container directory entries
     * @param images the list the images are added to
     */
    private static void index(byte[] data, Segment segment, List<String[]> semantics, List<Image> images) {
        var header = segment.offset() + MPF_ID.length;
        var end = segment.offset() + segment.length();
        if (header + 8 > end) {
            return;
        }
        boolean little;
        if (data[header] == 'I' && data[header + 1] == 'I') {
            little = true;
        } else if (data[header] == 'M' && data[header + 1] == 'M') {
            little = false;
        } else {
            return;
        }
        var ifd = header + ByteArrays.u32(data, header + 4, little);
        if (ifd < header || ifd + 2 > end) {
            return;
        }
        var count = ByteArrays.u16(data, (int) ifd, little);
        for (var i = 0; i < count; i++) {
            var entry = (int) ifd + 2 + 12 * i;
            if (entry + 12 > end) {
                return;
            }
            if (ByteArrays.u16(data, entry, little) != MP_ENTRY) {
                continue;
            }
            var length = ByteArrays.u32(data, entry + 4, little);
            var at = header + ByteArrays.u32(data, entry + 8, little);
            if (length < 16 || at < header || at + length > end) {
                return;
            }
            var entries = (int) Math.min(length / 16, MAX_IMAGES);
            for (var image = 0; image < entries; image++) {
                var field = (int) at + image * 16;
                var attribute = ByteArrays.u32(data, field, little);
                var size = ByteArrays.u32(data, field + 4, little);
                var offset = ByteArrays.u32(data, field + 8, little);
                var start = image == 0 && offset == 0 ? 0 : header + offset;
                if (image == 0 && offset == 0 && (size == 0 || size > data.length)) {
                    size = imageEnd(data, 0);
                }
                if (size == 0 || start + size > data.length || !isSoi(data, (int) start)) {
                    continue;
                }
                var semantic = image < semantics.size() ? semantics.get(image)[0] : null;
                images.add(new Image((int) start, (int) size, (int) (attribute & 0xFFFFFF), semantic));
            }
            return;
        }
    }

    /**
     * Reads the entries of the XMP container directory.
     *
     * @param xmp the packet, or {@code null}
     * @return each entry's {@code Item:Semantic}, {@code Item:Length} and {@code Item:Padding} texts, in order,
     * {@code null} for absent fields
     */
    private static List<String[]> semantics(XmpPacket xmp) {
        if (xmp == null) {
            return List.of();
        }
        var directory = xmp.get(CONTAINER, "Directory").orElse(null);
        if (!(directory instanceof XmpValue.Array array)) {
            return List.of();
        }
        var result = new ArrayList<String[]>();
        for (var item : array.items()) {
            if (!(item instanceof XmpValue.Struct struct)) {
                result.add(new String[3]);
                continue;
            }
            var fields = struct.field(CONTAINER, "Item").filter(XmpValue.Struct.class::isInstance)
                    .map(XmpValue.Struct.class::cast).orElse(struct);
            result.add(new String[]{text(fields, "Semantic"), text(fields, "Length"), text(fields, "Padding")});
        }
        return result;
    }

    /**
     * {@return the text of a container item field, or {@code null}}
     */
    private static String text(XmpValue.Struct struct, String name) {
        return struct.field(ITEM, name).flatMap(XmpValue::asText).map(String::strip).orElse(null);
    }

    /**
     * {@return a non-negative decimal length, or 0 when absent or invalid}
     */
    private static int parseLength(String text) {
        if (text == null) {
            return 0;
        }
        try {
            var value = Long.parseLong(text);
            return value < 0 || value > Integer.MAX_VALUE ? 0 : (int) value;
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    /**
     * {@return whether an SOI marker starts at an index}
     */
    private static boolean isSoi(byte[] data, int at) {
        return at >= 0 && at + 2 <= data.length && (data[at] & 0xFF) == 0xFF && (data[at + 1] & 0xFF) == 0xD8;
    }

    /**
     * Reads the XMP packet of an image header, merged with its Extended XMP packet.
     *
     * @param data the file
     * @param segments the image header
     * @param limits the bounds of the packet
     * @return the packet, or {@code null} when the header has none or it does not parse
     */
    public static XmpPacket xmp(byte[] data, List<Segment> segments, MediaLimits limits) {
        byte[] main = null;
        var chunks = new ArrayList<byte[]>();
        for (var segment : segments) {
            if (segment.marker() != 0xE1) {
                continue;
            }
            if (main == null && segment.startsWith(data, JpegXmp.XMP_ID)) {
                var from = segment.offset() + JpegXmp.XMP_ID.length;
                main = java.util.Arrays.copyOfRange(data, from, segment.offset() + segment.length());
            } else if (segment.startsWith(data, JpegXmp.EXTENDED_XMP_ID)) {
                chunks.add(java.util.Arrays.copyOfRange(data, segment.offset(), segment.offset() + segment.length()));
            }
        }
        return main == null ? null : JpegXmp.merge(main, chunks, limits);
    }

    /**
     * Returns the ISO 21496-1 metadata of an image header.
     *
     * @param data the file
     * @param segments the image header
     * @return the bytes after the identifier of the first {@code APP2} ISO 21496-1 segment that holds more than
     * the two version fields, or {@code null}
     */
    public static byte[] isoMetadata(byte[] data, List<Segment> segments) {
        for (var segment : segments) {
            if (segment.marker() == 0xE2 && segment.startsWith(data, ISO_ID) && segment.length() > ISO_ID.length + 4) {
                return java.util.Arrays.copyOfRange(data, segment.offset() + ISO_ID.length,
                        segment.offset() + segment.length());
            }
        }
        return null;
    }
}
