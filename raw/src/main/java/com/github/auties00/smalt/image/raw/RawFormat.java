package com.github.auties00.smalt.image.raw;

/**
 * A camera raw file format {@link RawDecoder} recognizes.
 *
 * <p>Recognizing a format does not imply that every variant of it decodes:
 * {@link RawDecoder#decode(com.github.auties00.smalt.io.MediaSource, RawDecoderOptions)} raises
 * {@link com.github.auties00.smalt.MediaException.Unsupported} naming the feature it lacks (a
 * compression scheme, for example), and the embedded preview of such a file is often still
 * available through {@link RawDecoder#extractPreview(com.github.auties00.smalt.io.MediaSource)}.
 *
 * <p>This enum may gain constants in later releases; a {@code switch} over it needs a
 * {@code default} branch.
 */
public enum RawFormat {
    /**
     * Adobe Digital Negative.
     */
    DNG("dng"),

    /**
     * Canon CR2, a TIFF-based format with lossless JPEG sensor data.
     */
    CR2("cr2"),

    /**
     * Canon CR3, an ISO-BMFF format with CRX sensor data.
     */
    CR3("cr3"),

    /**
     * Canon CRW, the CIFF-based format of older Canon cameras, with sensor data in Canon's lossless
     * compression.
     */
    CRW("crw"),

    /**
     * Nikon NEF and NRW.
     */
    NEF("nef"),

    /**
     * Sony ARW, ARQ, SR2 and SRF.
     */
    ARW("arw"),

    /**
     * Fujifilm RAF.
     */
    RAF("raf"),

    /**
     * Olympus and OM System ORF.
     */
    ORF("orf"),

    /**
     * Panasonic RW2 and RAW, and Leica RWL.
     */
    RW2("rw2"),

    /**
     * Pentax PEF.
     */
    PEF("pef"),

    /**
     * Samsung SRW.
     */
    SRW("srw"),

    /**
     * Epson ERF, the format of the R-D1 rangefinder cameras.
     */
    ERF("erf"),

    /**
     * Kodak DCR and KDC, and the TIFF-structured raw files of Kodak's DCS cameras.
     */
    DCR("dcr"),

    /**
     * Hasselblad 3FR and FFF, TIFF/EP-structured formats with lossless JPEG or uncompressed sensor data.
     * The constant takes the name of the FFF extension, since a Java name cannot begin with a digit.
     */
    FFF("3fr"),

    /**
     * Leaf MOS, the format of Leaf's digital backs, with uncompressed or lossless JPEG sensor data.
     */
    MOS("mos"),

    /**
     * Phase One IIQ, the format of Phase One's digital backs and of Leaf's Credo backs. Its files give
     * their metadata, not their sensor data.
     */
    IIQ("iiq"),

    /**
     * Mamiya MEF, the format of the ZD camera and back, with uncompressed 12-bit sensor data.
     */
    MEF("mef"),

    /**
     * Minolta and Konica Minolta MRW, with uncompressed 12-bit sensor data.
     */
    MRW("mrw"),

    /**
     * Sigma X3F, the format of Foveon sensors, whose three stacked layers give three samples per
     * photosite, read as linear raw data: the TRUE coding of the DP, SD15 and Merrill cameras and
     * the older Huffman coding of the SD14. The Quattro generation's layers differ
     * in resolution; those files give their metadata and previews, not their sensor data.
     */
    X3F("x3f"),

    /**
     * Another TIFF/EP-structured raw format.
     */
    OTHER("raw");

    private final String extension;

    RawFormat(String extension) {
        this.extension = extension;
    }

    /**
     * {@return the usual file extension of the format, lower case and without the dot, such as
     * {@code "nef"}}
     */
    public String extension() {
        return extension;
    }
}
