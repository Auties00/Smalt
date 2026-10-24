package com.github.auties00.smalt.internal.gainmap;

import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.gainmap.GainMap;
import com.github.auties00.smalt.image.gainmap.GainMapChannel;
import com.github.auties00.smalt.image.gainmap.GainMapMetadata;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.metadata.XmpPacket;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Converts Apple's HDR gain maps (the {@code urn:com:apple:photo:2020:aux:hdrgainmap} auxiliary image of HEIC and
 * the gain map of Apple JPEG files) to the ISO 21496-1 model, following Apple's "Applying Apple HDR effect to your
 * photos".
 *
 * <p>Apple's gain map is a single-channel map encoded with the BT.709 transfer function: its linearized sample
 * {@code g} scales the linear base value as {@code hdr = sdr * (1 + (headroom - 1) * g)}. The headroom comes from
 * the floats of the tags 33 and 48 of the Apple maker note in the EXIF block, or without them from the
 * {@code HDRGainMap:HDRGainMapHeadroom} XMP property. The converted gain map stores
 * {@code log2(1 + (headroom - 1) * g) / log2(headroom)} as 16-bit gray samples, with a log2 gain range of 0 to
 * {@code log2(headroom)}, no offsets, and headrooms 0 and {@code log2(headroom)}, so that applying it in full
 * gives Apple's result exactly up to the 16-bit quantization. This class is thread-safe.
 */
public final class AppleGainMaps {
    /**
     * The auxiliary image type of Apple's gain map.
     */
    public static final String URN = "urn:com:apple:photo:2020:aux:hdrgainmap";

    /**
     * The XMP namespace of Apple's gain map properties.
     */
    public static final String XMP_NAMESPACE = "http://ns.apple.com/HDRGainMap/1.0/";

    /**
     * The signature that starts an Apple maker note.
     */
    private static final byte[] MAKER_NOTE = "Apple iOS\0".getBytes(StandardCharsets.ISO_8859_1);

    private AppleGainMaps() {
        throw new AssertionError();
    }

    /**
     * Converts an Apple gain map.
     *
     * @param image the decoded gain map image; its first channel is read
     * @param exif the EXIF block of the main image (a TIFF structure), or {@code null}
     * @param xmp the XMP packets that may declare the gain map: those of the gain map image and of the main
     * image, {@code null} entries ignored
     * @return the gain map, or empty when no packet holds {@code HDRGainMap:HDRGainMapVersion} or the headroom
     * is unknown
     */
    public static Optional<GainMap> convert(Picture image, byte[] exif, List<XmpPacket> xmp) {
        var declared = false;
        var fallback = OptionalDouble.empty();
        for (var packet : xmp) {
            if (packet == null) {
                continue;
            }
            declared |= packet.text(XMP_NAMESPACE, "HDRGainMapVersion").isPresent();
            if (fallback.isEmpty()) {
                fallback = packet.text(XMP_NAMESPACE, "HDRGainMapHeadroom").map(String::strip).flatMap(text -> {
                    try {
                        var value = Double.parseDouble(text);
                        return Double.isFinite(value) && value >= 1 ? Optional.of(value) : Optional.empty();
                    } catch (NumberFormatException exception) {
                        return Optional.empty();
                    }
                }).map(OptionalDouble::of).orElse(OptionalDouble.empty());
            }
        }
        if (!declared) {
            return Optional.empty();
        }
        var headroom = exif == null ? OptionalDouble.empty() : makerHeadroom(exif);
        if (headroom.isEmpty()) {
            headroom = fallback;
        }
        if (headroom.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(convert(image, headroom.getAsDouble()));
    }

    /**
     * Converts an Apple gain map of a known headroom.
     *
     * @param image the decoded gain map image; its first channel is read
     * @param headroom the linear headroom, at least 1
     * @return the gain map
     */
    public static GainMap convert(Picture image, double headroom) {
        var stops = StrictMath.log(headroom) / StrictMath.log(2);
        var bits = image.bitDepth() <= 8 ? 8 : 16;
        var max = (1 << bits) - 1;
        var table = new short[max + 1];
        for (var code = 0; code <= max; code++) {
            var linear = bt709Inverse((double) code / max);
            var gain = stops > 0 ? StrictMath.log(1 + (headroom - 1) * linear) / StrictMath.log(2) / stops : 0;
            table[code] = (short) Math.round(Math.min(Math.max(gain, 0), 1) * 65535);
        }
        var width = image.width();
        var height = image.height();
        var result = Channel16Picture.allocate(width, height, PixelLayout.GRAY);
        var out = result.pixels();
        if (bits == 8) {
            var codes = ImageOps.toChannel8(image);
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    out[y * result.stride() + x] = table[codes.pixels()[y * codes.stride() + x * codes.channelCount()]
                            & 0xFF];
                }
            }
        } else {
            var codes = ImageOps.toChannel16(image);
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    out[y * result.stride() + x] = table[codes.pixels()[y * codes.stride() + x * codes.channelCount()]
                            & 0xFFFF];
                }
            }
        }
        var metadata = new GainMapMetadata(0, Math.max(stops, 0),
                List.of(new GainMapChannel(0, Math.max(stops, 0), 1, 0, 0)), true);
        return new GainMap(metadata, result);
    }

    /**
     * Computes the headroom of Apple's formula from the stop values of the maker note tags 33 and 48.
     *
     * @param maker33 the value of tag 33
     * @param maker48 the value of tag 48
     * @return the linear headroom, at least 1
     */
    public static double headroom(double maker33, double maker48) {
        double stops;
        if (maker33 < 1.0) {
            stops = maker48 <= 0.01 ? -20.0 * maker48 + 1.8 : -0.101 * maker48 + 1.601;
        } else {
            stops = maker48 <= 0.01 ? -70.0 * maker48 + 3.0 : -0.303 * maker48 + 2.303;
        }
        return StrictMath.pow(2, Math.max(stops, 0));
    }

    /**
     * {@return the inverse of the BT.709 OETF}
     */
    private static double bt709Inverse(double signal) {
        return signal < 0.081 ? signal / 4.5 : StrictMath.pow((signal + 0.099) / 1.099, 1 / 0.45);
    }

    /**
     * Reads the headroom from the Apple maker note of an EXIF block.
     *
     * @param exif the TIFF structure
     * @return the headroom, or empty when the maker note, or its tags 33 and 48, are absent or malformed
     */
    static OptionalDouble makerHeadroom(byte[] exif) {
        try {
            var tiff = new Tiff(exif, 0);
            var ifd0 = tiff.u32(4);
            var exifIfd = tiff.entry(ifd0, 0x8769);
            if (exifIfd == null) {
                return OptionalDouble.empty();
            }
            var note = tiff.entry(tiff.u32(exifIfd[2]), 0x927C);
            if (note == null || note[0] != 7) {
                return OptionalDouble.empty();
            }
            var at = note[1] <= 4 ? note[2] : (int) tiff.u32(note[2]);
            var length = note[1];
            if (length < MAKER_NOTE.length + 6 || at < 0 || at + length > exif.length) {
                return OptionalDouble.empty();
            }
            for (var i = 0; i < MAKER_NOTE.length; i++) {
                if (exif[at + i] != MAKER_NOTE[i]) {
                    return OptionalDouble.empty();
                }
            }
            var maker = new Tiff(java.util.Arrays.copyOfRange(exif, at, at + length), MAKER_NOTE.length + 2);
            var tag33 = maker.real(14, 33);
            var tag48 = maker.real(14, 48);
            if (Double.isNaN(tag33) || Double.isNaN(tag48)) {
                return OptionalDouble.empty();
            }
            return OptionalDouble.of(headroom(tag33, tag48));
        } catch (IndexOutOfBoundsException exception) {
            return OptionalDouble.empty();
        }
    }

    /**
     * Reads the directories of a TIFF structure, bounds-checked through {@link IndexOutOfBoundsException}.
     */
    private static final class Tiff {
        /**
         * The structure.
         */
        private final byte[] data;

        /**
         * Whether the structure is little-endian.
         */
        private final boolean little;

        /**
         * Reads the byte order mark of a structure.
         *
         * @param data the bytes
         * @param order the index of the byte order mark ({@code II} or {@code MM})
         * @throws IndexOutOfBoundsException if the mark is neither
         */
        Tiff(byte[] data, int order) {
            this.data = data;
            if (data[order] == 'I' && data[order + 1] == 'I') {
                little = true;
            } else if (data[order] == 'M' && data[order + 1] == 'M') {
                little = false;
            } else {
                throw new IndexOutOfBoundsException("no TIFF byte order");
            }
        }

        /**
         * {@return the unsigned 16-bit value at an index}
         */
        int u16(int at) {
            Objects.checkFromIndexSize(at, 2, data.length);
            return little ? (data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8 : (data[at] & 0xFF) << 8 | data[at + 1] & 0xFF;
        }

        /**
         * {@return the unsigned 32-bit value at an index, as an int index}
         */
        int u32(long at) {
            Objects.checkFromIndexSize(at, 4, data.length);
            var i = (int) at;
            long value = little
                    ? (data[i] & 0xFF) | (data[i + 1] & 0xFF) << 8 | (data[i + 2] & 0xFF) << 16 | (long) (data[i + 3] & 0xFF) << 24
                    : (long) (data[i] & 0xFF) << 24 | (data[i + 1] & 0xFF) << 16 | (data[i + 2] & 0xFF) << 8 | data[i + 3] & 0xFF;
            if (value > Integer.MAX_VALUE) {
                throw new IndexOutOfBoundsException("offset " + value);
            }
            return (int) value;
        }

        /**
         * Finds an entry of a directory.
         *
         * @param ifd the index of the directory
         * @param tag the tag number
         * @return the type, the count and the index of the value field, or {@code null} when absent
         */
        int[] entry(long ifd, int tag) {
            var count = u16((int) ifd);
            for (var i = 0; i < count; i++) {
                var at = (int) ifd + 2 + 12 * i;
                if (u16(at) == tag) {
                    return new int[]{u16(at + 2), u32(at + 4), at + 8};
                }
            }
            return null;
        }

        /**
         * Reads a real-valued entry: a float, a signed or unsigned rational, or an integer.
         *
         * @param ifd the index of the directory
         * @param tag the tag number
         * @return the value, or NaN when absent or of another type
         */
        double real(int ifd, int tag) {
            var entry = entry(ifd, tag);
            if (entry == null || entry[1] < 1) {
                return Double.NaN;
            }
            return switch (entry[0]) {
                case 11 -> Float.intBitsToFloat((int) u32Raw(entry[2]));
                case 5, 10 -> {
                    var at = u32(entry[2]);
                    var numerator = u32Raw(at);
                    var denominator = u32Raw(at + 4);
                    if (entry[0] == 10) {
                        numerator = (int) numerator;
                        denominator = (int) denominator;
                    }
                    yield denominator == 0 ? Double.NaN : (double) numerator / denominator;
                }
                case 3 -> u16(entry[2]);
                case 4 -> u32Raw(entry[2]);
                case 8 -> (short) u16(entry[2]);
                case 9 -> (int) u32Raw(entry[2]);
                default -> Double.NaN;
            };
        }

        /**
         * {@return the raw unsigned 32-bit value at an index}
         */
        long u32Raw(int at) {
            Objects.checkFromIndexSize(at, 4, data.length);
            return little
                    ? (data[at] & 0xFFL) | (data[at + 1] & 0xFFL) << 8 | (data[at + 2] & 0xFFL) << 16 | (data[at + 3] & 0xFFL) << 24
                    : (data[at] & 0xFFL) << 24 | (data[at + 1] & 0xFFL) << 16 | (data[at + 2] & 0xFFL) << 8 | data[at + 3] & 0xFFL;
        }
    }
}
