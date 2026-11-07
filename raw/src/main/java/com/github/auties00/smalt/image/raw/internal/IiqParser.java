package com.github.auties00.smalt.image.raw.internal;

/**
 * Parses a Phase One IIQ file, the format of Phase One's digital backs and of Leaf's Credo backs: a
 * TIFF structure whose header is followed, at byte 8, by Phase One's own block (it starts with
 * {@code IIII} and {@code CwaR}), which holds the sensor data and its metadata.
 *
 * <p>The TIFF directories give the camera, the capture settings and the EXIF metadata. The sensor
 * data is not read: the file is described and its decoding rejected as unsupported.
 *
 * @implNote This implementation recognizes the block by the signature the CC0 sample files share.
 * Phase One publishes no description of the block or of its compressions (IIQ L, IIQ S and the
 * uncompressed sensor files), and no permissively licensed decoder of them exists.
 */
final class IiqParser {
    /**
     * The signature of Phase One's block, at byte 8 of the file.
     */
    private static final byte[] SIGNATURE = {'I', 'I', 'I', 'I', 'C', 'w', 'a', 'R'};

    /**
     * Prevents instantiation.
     */
    private IiqParser() {
        throw new AssertionError();
    }

    /**
     * {@return whether the first bytes of a TIFF file carry Phase One's block}
     *
     * @param head the first bytes of the file
     * @param length the number of bytes of {@code head} read from the file
     */
    static boolean matches(byte[] head, int length) {
        if (length < 8 + SIGNATURE.length) {
            return false;
        }
        for (var index = 0; index < SIGNATURE.length; index++) {
            if (head[8 + index] != SIGNATURE[index]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Parses the file.
     *
     * @param reader the TIFF structure
     * @param file the description to fill
     */
    static void parse(TiffReader reader, RawFile file) {
        new TiffTree(reader).describe(file);
        // TODO: decode the sensor data of Phase One's block (IIQ L, IIQ S and uncompressed) once a specification or
        //  a permissively licensed reference describes it (its only public descriptions are GPL and LGPL decoders)
        file.unsupported = "the sensor data of Phase One IIQ files is not supported";
    }
}
