package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifReader;
import com.github.auties00.smalt.image.exif.ExifValue;
import com.github.auties00.smalt.image.exif.ExifWriter;
import com.github.auties00.smalt.internal.edit.EditPlan;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.XmpPacket;

import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the metadata of a TIFF file, whose first directory is itself an EXIF directory.
 *
 * <p>The descriptive entries of IFD0, with the Exif, GPS and interoperability directories, form
 * the EXIF block; the XMP packet (tag 700), the IPTC-IIM data (tag 33723) and the ICC profile
 * (tag 34675) come from their IFD0 entries.
 */
final class TiffFile implements ImageFile {
    /**
     * The tag of the XMP packet.
     */
    private static final int XMP = 700;

    /**
     * The tag of the IPTC-IIM data.
     */
    private static final int IPTC = 33723;

    /**
     * The tag of the ICC profile.
     */
    private static final int ICC = 34675;

    /**
     * The tags of IFD0 that describe the image layout rather than the picture, left out of the
     * EXIF block.
     */
    private static final Set<Integer> LAYOUT = Set.of(273, 279, 288, 289, 324, 325, 330, 513, 514, 34377, XMP, IPTC, ICC);

    /**
     * The source.
     */
    private final MediaSource source;

    /**
     * The parsed directories.
     */
    private final ExifData data;

    /**
     * Whether the file is little endian.
     */
    private final boolean little;

    /**
     * Reads the file and parses its first directory.
     *
     * @param source the TIFF file
     * @param limits the resource bounds
     * @throws MediaException.Malformed if the TIFF header is invalid
     */
    TiffFile(MediaSource source, MediaLimits limits) {
        this.source = source;
        var file = ImageFiles.read(source, 0, source.size(), limits);
        this.data = ExifReader.parse(file);
        this.little = file[0] == 'I';
    }

    @Override
    public ImageMetadata metadata() {
        var ifd0 = new LinkedHashMap<Integer, ExifValue>();
        for (var entry : data.ifd0().entrySet()) {
            if (!LAYOUT.contains(entry.getKey())) {
                ifd0.put(entry.getKey(), entry.getValue());
            }
        }
        var exif = ExifWriter.write(new ExifData(ifd0, data.exif(), data.gps(), data.interop(), Map.of(), Optional.empty()),
                ByteOrder.BIG_ENDIAN);
        XmpPacket xmp = null;
        var xmpBytes = bytes(data.ifd0().get(XMP), little);
        if (xmpBytes != null) {
            try {
                xmp = XmpPacket.parse(xmpBytes);
            } catch (MediaException.Malformed ignored) {
                // a packet that does not parse is left out
            }
        }
        var iptcBytes = bytes(data.ifd0().get(IPTC), little);
        var iptc = iptcBytes != null ? IptcData.parse(iptcBytes) : null;
        return new ImageMetadata(ImageFormat.TIFF, exif, xmp, iptc != null && !iptc.isEmpty() ? iptc : null,
                bytes(data.ifd0().get(ICC), little), List.of(), Map.of());
    }

    /**
     * Returns the bytes an entry holds.
     *
     * @param value the entry, or {@code null}
     * @param little whether the file stores integers little endian
     * @return the bytes of an {@code UNDEFINED} or {@code BYTE} entry, or the stored bytes of a
     * {@code SHORT} or {@code LONG} entry, or {@code null}
     */
    private static byte[] bytes(ExifValue value, boolean little) {
        return switch (value) {
            case null -> null;
            case ExifValue.Undefined undefined -> undefined.bytes();
            case ExifValue.Integers integers -> {
                var size = integers.type() == ExifValue.Type.LONG || integers.type() == ExifValue.Type.SLONG ? 4
                        : integers.type() == ExifValue.Type.SHORT || integers.type() == ExifValue.Type.SSHORT ? 2 : 1;
                var values = integers.values();
                var result = new byte[values.length * size];
                for (var i = 0; i < values.length; i++) {
                    switch (size) {
                        case 4 -> ByteArrays.put32(result, 4 * i, values[i], little);
                        case 2 -> ByteArrays.put16(result, 2 * i, (int) values[i], little);
                        default -> result[i] = (byte) values[i];
                    }
                }
                yield result;
            }
            default -> null;
        };
    }

    @Override
    public EditPlan plan(Resolved target) {
        if (!target.keepsAll()) {
            // TODO: rewrite the metadata entries of IFD0, keeping the image data and the other directories
            throw new MediaException.Unsupported("tiff", "editing the metadata of TIFF files is not supported");
        }
        return EditPlan.builder().copy(0, source.size()).build();
    }
}
