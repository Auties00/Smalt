package com.github.auties00.smalt.image;

import com.github.auties00.smalt.internal.io.ByteArrays;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/**
 * Enumerates the still and animated image formats this module can recognize.
 *
 * <p>Recognition by {@link #detect(byte[], int)} looks only at leading magic bytes, never at a
 * file name or a declared mimetype, so a mislabeled file is still routed to the right decoder.
 * Recognizing a format does not imply this module can decode it; decoders raise
 * {@link com.github.auties00.smalt.MediaException.Unsupported} for recognized formats they
 * do not implement.
 *
 * <p>This enum may gain constants in later releases; a {@code switch} over it needs a
 * {@code default} branch.
 *
 * <p>Instances are immutable and thread-safe.
 */
public enum ImageFormat {
    /**
     * JPEG (JFIF, EXIF, Adobe), magic {@code FF D8 FF}.
     */
    JPEG("image/jpeg"),

    /**
     * Portable Network Graphics, magic {@code 89 50 4E 47 0D 0A 1A 0A}.
     */
    PNG("image/png"),

    /**
     * Graphics Interchange Format, magic {@code GIF87a} or {@code GIF89a}.
     */
    GIF("image/gif"),

    /**
     * WebP, a RIFF container with form type {@code WEBP}.
     */
    WEBP("image/webp"),

    /**
     * Windows bitmap, magic {@code BM}.
     */
    BMP("image/bmp"),

    /**
     * HEIF with an HEVC-coded primary image (HEIC), an ISO-BMFF file whose {@code ftyp} brands
     * include {@code heic}, {@code heix}, {@code heim}, {@code heis}, {@code hevc} or {@code hevx};
     * a file with only the generic HEIF brands ({@code mif1}, {@code mif2}, {@code msf1} or
     * {@code miaf}) and no AVIF brand is taken as HEIC too, the codec HEIF files use most.
     */
    HEIC("image/heic"),

    /**
     * HEIF with an AV1-coded primary image (AVIF), an ISO-BMFF file whose {@code ftyp} brands
     * include {@code avif} or {@code avis}.
     */
    AVIF("image/avif"),

    /**
     * Tagged Image File Format, magic {@code II*\0} or {@code MM\0*}, or BigTIFF, magic
     * {@code II+\0} or {@code MM\0+}.
     */
    TIFF("image/tiff"),

    /**
     * Windows icon (ICO) or cursor (CUR), a directory of images starting {@code 00 00 01 00} (icon)
     * or {@code 00 00 02 00} (cursor) whose first entry is consistent.
     */
    ICO("image/vnd.microsoft.icon"),

    /**
     * JPEG 2000: a JP2 or JPX file, starting with the 12-byte signature box
     * {@code 00 00 00 0C 6A 50 20 20 0D 0A 87 0A}, or a raw codestream, magic {@code FF 4F FF 51}.
     */
    JP2("image/jp2"),

    /**
     * JPEG XL, either a bare codestream, magic {@code FF 0A}, or an ISO-BMFF container starting
     * with the 12-byte signature box {@code 00 00 00 0C 4A 58 4C 20 0D 0A 87 0A}.
     */
    JXL("image/jxl"),

    /**
     * Adobe Digital Negative (DNG), a TIFF-structured camera raw image whose first directory
     * holds the {@code DNGVersion} tag (50706).
     *
     * <p>A DNG file starts like any TIFF file, so {@link #detect(byte[], int)} recognizes it only
     * when its first directory lies within the bytes it is given, and reports {@link #TIFF}
     * otherwise; {@link Images#detect(com.github.auties00.smalt.io.MediaSource)} follows the
     * directories through the source and always tells a DNG file from a TIFF one.
     */
    DNG("image/x-adobe-dng"),

    /**
     * A camera raw image in a vendor format other than DNG: Canon CR2 ({@code II*} with
     * {@code CR} at offset 8), CR3 (an ISO-BMFF file of major brand {@code crx }) and CRW, Nikon
     * NEF and NRW, Sony ARW, SR2 and SRF, Fujifilm RAF ({@code FUJIFILMCCD-RAW}), Olympus ORF
     * ({@code IIRO}, {@code IIRS} or {@code MMOR}), Panasonic and Leica RW2 and RWL
     * ({@code IIU}), Pentax PEF, Samsung SRW, Minolta MRW and Sigma X3F ({@code FOVb}), and any
     * other TIFF-structured file whose directories hold a color filter array or linear raw
     * image (photometric interpretation 32803 or 34892) or a vendor raw compression.
     *
     * <p>The TIFF-structured vendor formats other than CR2 start like any TIFF file, so
     * {@link #detect(byte[], int)} recognizes them only when their directories lie within the
     * bytes it is given, and reports {@link #TIFF} otherwise;
     * {@link Images#detect(com.github.auties00.smalt.io.MediaSource)} follows the directories
     * through the source. Which vendor format a file uses is reported by the raw decoder.
     */
    RAW("image/x-dcraw");

    /**
     * The number of leading bytes {@link #detect(byte[], int)} needs to recognize every format
     * other than the TIFF-structured {@link #DNG} and {@link #RAW} files, which it recognizes
     * when their directories lie within the bytes it is given.
     */
    public static final int DETECTION_LENGTH = 64;

    /**
     * The IANA media type of the format.
     */
    private final String mimeType;

    /**
     * Constructs a constant.
     *
     * @param mimeType the IANA media type
     */
    ImageFormat(String mimeType) {
        this.mimeType = mimeType;
    }

    /**
     * Returns the IANA media type of the format.
     *
     * @return the media type, for example {@code image/jpeg}
     */
    public String mimeType() {
        return mimeType;
    }

    /**
     * Recognizes the image format from the leading bytes of an input.
     *
     * <p>A TIFF-structured camera raw file ({@link #DNG}, and the {@link #RAW} formats that use
     * the plain TIFF signature) is told from a TIFF file by the directories it holds, so it is
     * recognized as such only when those directories lie within the first {@code length} bytes;
     * otherwise it is reported as {@link #TIFF}.
     *
     * @param header the leading bytes of the input
     * @param length the number of valid bytes in {@code header}; {@link #DETECTION_LENGTH} bytes suffice to recognize
     * every format other than the TIFF-structured raw ones
     * @return the recognized format, or empty when the bytes match none
     * @throws IndexOutOfBoundsException if {@code length} is negative or exceeds the array length
     */
    public static Optional<ImageFormat> detect(byte[] header, int length) {
        Objects.requireNonNull(header, "header");
        if (length < 0 || length > header.length) {
            throw new IndexOutOfBoundsException("length " + length + " is outside 0.." + header.length);
        }
        return Optional.ofNullable(recognize(header, length));
    }

    /**
     * Recognizes the format as {@link #detect(byte[], int)} does, from arguments already checked,
     * returning {@code null} when the bytes match none.
     */
    private static ImageFormat recognize(byte[] header, int length) {
        if (length >= 3 && u8(header, 0) == 0xFF && u8(header, 1) == 0xD8 && u8(header, 2) == 0xFF) {
            return JPEG;
        }
        if (length >= 8 && u8(header, 0) == 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G'
                && header[4] == 0x0D && header[5] == 0x0A && header[6] == 0x1A && header[7] == 0x0A) {
            return PNG;
        }
        if (length >= 6 && header[0] == 'G' && header[1] == 'I' && header[2] == 'F' && header[3] == '8'
                && (header[4] == '7' || header[4] == '9') && header[5] == 'a') {
            return GIF;
        }
        if (length >= 12 && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
            return WEBP;
        }
        if (length >= 14 && header[0] == 'B' && header[1] == 'M') {
            return BMP;
        }
        if (length >= 4 && ((header[0] == 'I' && header[1] == 'I' && (header[2] == 42 || header[2] == 43) && header[3] == 0)
                || (header[0] == 'M' && header[1] == 'M' && header[2] == 0 && (header[3] == 42 || header[3] == 43)))) {
            return RawDetection.classifyTiff(RawDetection.over(header, length));
        }
        if (RawDetection.hasRawSignature(header, length)) {
            return RAW;
        }
        if (isJxl(header, length)) {
            return JXL;
        }
        if (isIcon(header, length)) {
            return ICO;
        }
        if (isJp2(header, length)) {
            return JP2;
        }
        return detectHeif(header, length);
    }

    /**
     * Recognizes a JPEG XL codestream or container from its signature.
     *
     * @param header the leading bytes of the input
     * @param length the number of valid bytes in {@code header}
     * @return whether the bytes start a JPEG XL file
     */
    private static boolean isJxl(byte[] header, int length) {
        if (length >= 2 && u8(header, 0) == 0xFF && u8(header, 1) == 0x0A) {
            return true;
        }
        return length >= 12 && header[0] == 0 && header[1] == 0 && header[2] == 0 && header[3] == 0x0C
                && header[4] == 'J' && header[5] == 'X' && header[6] == 'L' && header[7] == ' '
                && header[8] == 0x0D && header[9] == 0x0A && u8(header, 10) == 0x87 && header[11] == 0x0A;
    }

    /**
     * Recognizes an icon or cursor directory from its header and first entry.
     *
     * <p>The four magic bytes alone also start other files (a Targa image, for example), so the
     * image count must be positive, the first entry's reserved byte zero, its data size positive,
     * and its data must start after the directory.
     *
     * @param header the leading bytes of the input
     * @param length the number of valid bytes in {@code header}
     * @return whether the bytes start an icon or cursor file
     */
    private static boolean isIcon(byte[] header, int length) {
        if (length < 22 || header[0] != 0 || header[1] != 0 || (header[2] != 1 && header[2] != 2) || header[3] != 0) {
            return false;
        }
        var count = ByteArrays.u16le(header, 4);
        if (count == 0 || header[9] != 0) {
            return false;
        }
        var size = ByteArrays.u32le(header, 14);
        var offset = ByteArrays.u32le(header, 18);
        return size > 0 && offset >= 6L + 16L * count;
    }

    /**
     * Recognizes a JPEG 2000 file from its signature box, or a raw codestream from its
     * {@code SOC} and {@code SIZ} markers.
     *
     * @param header the leading bytes of the input
     * @param length the number of valid bytes in {@code header}
     * @return whether the bytes start a JPEG 2000 file or codestream
     */
    private static boolean isJp2(byte[] header, int length) {
        if (length >= 4 && u8(header, 0) == 0xFF && u8(header, 1) == 0x4F && u8(header, 2) == 0xFF
                && u8(header, 3) == 0x51) {
            return true;
        }
        return length >= 12 && header[0] == 0 && header[1] == 0 && header[2] == 0 && header[3] == 0x0C
                && header[4] == 'j' && header[5] == 'P' && header[6] == ' ' && header[7] == ' '
                && header[8] == 0x0D && header[9] == 0x0A && u8(header, 10) == 0x87 && header[11] == 0x0A;
    }

    /**
     * Recognizes a HEIF-family file from the brands of its leading {@code ftyp} box, or a Canon
     * CR3 raw image from its major brand {@code crx }.
     *
     * @param header the leading bytes of the input
     * @param length the number of valid bytes in {@code header}
     * @return {@link #HEIC}, {@link #AVIF}, {@link #RAW}, or {@code null} when the input is neither a HEIF image nor a
     * CR3 file
     */
    private static ImageFormat detectHeif(byte[] header, int length) {
        if (length < 16 || header[4] != 'f' || header[5] != 't' || header[6] != 'y' || header[7] != 'p') {
            return null;
        }
        if (header[8] == 'c' && header[9] == 'r' && header[10] == 'x' && header[11] == ' ') {
            return RAW;
        }
        var boxSize = ByteArrays.u32be(header, 0);
        var end = (int) Math.min(length, boxSize < 16 ? length : boxSize);
        ImageFormat match = null;
        for (var offset = 8; offset + 4 <= end; offset += 4) {
            if (offset == 12) {
                continue;
            }
            var brand = new String(header, offset, 4, StandardCharsets.ISO_8859_1);
            switch (brand) {
                case "avif", "avis" -> {
                    return AVIF;
                }
                case "heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "mif2", "msf1", "miaf" -> match = HEIC;
                default -> {
                }
            }
        }
        return match;
    }

    /**
     * Reads one unsigned byte.
     *
     * @param data the array
     * @param offset the index to read
     * @return the byte as a value from 0 to 255
     */
    private static int u8(byte[] data, int offset) {
        return data[offset] & 0xFF;
    }
}
