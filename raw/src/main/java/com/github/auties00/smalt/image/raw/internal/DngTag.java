package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.internal.tiff.TiffTypes;

/**
 * Holds one TIFF entry of a DNG file that a DNG file converted from it carries over unchanged: a rendering hint
 * or a description of the camera that the decoder does not interpret.
 *
 * @param tag the tag number
 * @param type the TIFF field type
 * @param count the number of values
 * @param value the values in little-endian byte order
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
     * {@code ProfileCopyright}, {@code AsShotProfileName}, {@code ProfileDynamicRange} and {@code ProfileGroupName}.
     */
    static final int[] PROFILE = {50941, 50942, 50934, 52551, 52552};

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
        if (!ifd.has(tag)) {
            return null;
        }
        byte[] value;
        try {
            value = ifd.bytes(tag);
        } catch (RuntimeException exception) {
            return null;
        }
        var type = ifd.type(tag);
        var size = switch (type) {
            case TiffTypes.SHORT, TiffTypes.SSHORT -> 2;
            case TiffTypes.LONG, TiffTypes.SLONG, TiffTypes.FLOAT, TiffTypes.RATIONAL, TiffTypes.SRATIONAL -> 4;
            case TiffTypes.DOUBLE -> 8;
            default -> 1;
        };
        if (size > 1 && !ifd.reader().little()) {
            value = value.clone();
            for (var at = 0; at + size <= value.length; at += size) {
                for (var i = 0; i < size / 2; i++) {
                    var swap = value[at + i];
                    value[at + i] = value[at + size - 1 - i];
                    value[at + size - 1 - i] = swap;
                }
            }
        }
        return new DngTag(tag, type, ifd.valueCount(tag), value);
    }
}
