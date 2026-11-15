package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.internal.tiff.TiffTypes;

/**
 * Holds one TIFF entry of a DNG file that a DNG file converted from it carries over unchanged: a rendering hint
 * or a description of the camera that the decoder does not interpret.
 *
 * @param tag the tag number
 * @param type the TIFF field type
 * @param count the number of values
 * @param value the values: in little-endian byte order when read by {@link #of}, in the directory's when read by
 * {@link #stored}
 */
record DngTag(int tag, int type, long count, byte[] value) {
    /**
     * The tags of the first directory carried over: {@code BaselineNoise}, {@code BaselineSharpness},
     * {@code LinearResponseLimit}, {@code CameraSerialNumber}, {@code LensInfo}, {@code ShadowScale},
     * {@code OriginalRawFileName}, {@code NoiseReductionApplied} and {@code NoiseProfile}.
     */
    static final int[] FIRST = {50731, 50732, 50734, 50735, 50736, 50739, 50827, 50935, 51041};

    /**
     * The tags of the primary camera profile carried over with it: {@code ProfileEmbedPolicy},
     * {@code ProfileCopyright} and {@code ReductionMatrix1} to {@code 3}.
     */
    static final int[] PROFILE = {50941, 50942, 50725, 50726, 52538};

    /**
     * The tags of the raw image's directory carried over: {@code BayerGreenSplit}, {@code ChromaBlurRadius},
     * {@code AntiAliasStrength}, {@code BestQualityScale}, {@code NoiseReductionApplied} and {@code NoiseProfile}.
     */
    static final int[] RAW = {50733, 50737, 50738, 50780, 50935, 51041};

    /**
     * Reads a tag of a directory, in little-endian byte order.
     *
     * @param ifd the directory
     * @param tag the tag number
     * @return the entry, or {@code null} when the directory lacks it or its value cannot be read
     */
    static DngTag of(Ifd ifd, int tag) {
        var entry = stored(ifd, tag);
        return entry != null ? entry.littleEndian(ifd.reader().little()) : null;
    }

    /**
     * Reads a tag of a directory, in the directory's byte order.
     *
     * @param ifd the directory
     * @param tag the tag number
     * @return the entry, or {@code null} when the directory lacks it or its value cannot be read
     */
    static DngTag stored(Ifd ifd, int tag) {
        if (!ifd.has(tag)) {
            return null;
        }
        byte[] value;
        try {
            value = ifd.bytes(tag);
        } catch (RuntimeException exception) {
            return null;
        }
        return value != null ? new DngTag(tag, ifd.type(tag), ifd.valueCount(tag), value) : null;
    }

    /**
     * Returns this entry in little-endian byte order, each value of a numeric type reversed when the entry is
     * big-endian; bytes, text and undefined data are kept as they are.
     *
     * @param little whether the entry is little-endian
     * @return the little-endian entry
     */
    DngTag littleEndian(boolean little) {
        var size = switch (type) {
            case TiffTypes.SHORT, TiffTypes.SSHORT -> 2;
            case TiffTypes.LONG, TiffTypes.SLONG, TiffTypes.FLOAT, TiffTypes.RATIONAL, TiffTypes.SRATIONAL -> 4;
            case TiffTypes.DOUBLE -> 8;
            default -> 1;
        };
        if (size == 1 || little) {
            return this;
        }
        var swapped = value.clone();
        for (var at = 0; at + size <= swapped.length; at += size) {
            for (var i = 0; i < size / 2; i++) {
                var swap = swapped[at + i];
                swapped[at + i] = swapped[at + size - 1 - i];
                swapped[at + size - 1 - i] = swap;
            }
        }
        return new DngTag(tag, type, count, swapped);
    }
}
