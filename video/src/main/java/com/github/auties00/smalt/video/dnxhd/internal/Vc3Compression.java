package com.github.auties00.smalt.video.dnxhd.internal;

import java.util.List;
import java.util.Optional;

/**
 * Describes one VC-3 compression ID (SMPTE ST 2019-1 Annex C): its raster (for the HD profile),
 * scan, sample bit depth, chroma layout, code tables, quantization weights and compressed frame
 * size.
 *
 * @param cid             the compression ID
 * @param resolutionIndependent whether the ID belongs to the RI profile (DNxHR), whose raster is
 *                        free and whose frame size scales with the macroblock count
 * @param width           the samples per line of an HD raster, or {@code 0}
 * @param lines           the active lines of a coding unit of an HD raster (a field for
 *                        field-encoded IDs), or {@code 0}
 * @param interlaced      whether the HD raster is interlaced
 * @param fieldEncoded    whether an interlaced frame is coded as two coding units
 * @param depths          the permitted sample bit depths, as a bit set ({@code 1 << depth})
 * @param chroma444       whether the ID codes 4:4:4 (Y'CbCr or RGB)
 * @param codeSet         the set of variable-length codes, from 0 to 5 (see {@link Vc3Tables})
 * @param weights         the quantization weight table of Annex D, from 1 to 11
 * @param dequantization  the inverse quantization parameter {@code p}: 8 or 32
 * @param frameSize       the compressed frame size of an HD raster, or the reference frame size
 *                        {@code C0} of an RI raster, in bytes
 * @param headerVersion   the header version number of the ID without alpha
 */
public record Vc3Compression(int cid, boolean resolutionIndependent, int width, int lines, boolean interlaced,
                             boolean fieldEncoded, int depths, boolean chroma444, int codeSet, int weights,
                             int dequantization, int frameSize, int headerVersion) {
    /**
     * The macroblock count of the reference raster of the RI frame sizes, {@code M_T}.
     */
    public static final int REFERENCE_MACROBLOCKS = 8160;

    /**
     * Every compression ID of ST 2019-1 Tables C.1 and C.2.
     *
     * <p>The weight tables are the ones Tables C.1 and C.2 assign: DNxHR HQX (ID 1271) uses Table D.1,
     * where ffmpeg's dnxhd codec uses Table D.4 of ID 1241, so the two disagree on HQX streams.
     */
    public static final List<Vc3Compression> ALL = List.of(
            hd(1235, 1920, 1080, false, false, 10, false, 0, 1, 8, 917504, 1),
            hd(1237, 1920, 1080, false, false, 8, false, 1, 2, 32, 606208, 1),
            hd(1238, 1920, 1080, false, false, 8, false, 2, 3, 32, 917504, 1),
            hd(1241, 1920, 540, true, true, 10, false, 0, 4, 8, 917504, 1),
            hd(1242, 1920, 540, true, true, 8, false, 1, 5, 32, 606208, 1),
            hd(1243, 1920, 540, true, true, 8, false, 2, 6, 32, 917504, 1),
            hd(1244, 1440, 540, true, true, 8, false, 1, 7, 32, 606208, 1),
            hd(1250, 1280, 720, false, false, 10, false, 3, 8, 8, 458752, 1),
            hd(1251, 1280, 720, false, false, 8, false, 4, 9, 32, 458752, 1),
            hd(1252, 1280, 720, false, false, 8, false, 5, 10, 32, 303104, 1),
            hd(1253, 1920, 1080, false, false, 8, false, 1, 2, 32, 188416, 1),
            hd(1256, 1920, 1080, false, false, 10, true, 0, 11, 32, 1835008, 2),
            hd(1258, 960, 720, false, false, 8, false, 5, 10, 32, 212992, 2),
            hd(1259, 1440, 1080, false, false, 8, false, 1, 2, 32, 417792, 2),
            hd(1260, 1440, 1080, true, false, 8, false, 1, 7, 32, 417792, 2),
            new Vc3Compression(1270, true, 0, 0, false, false, 1 << 10 | 1 << 12, true, 0, 11, 32, 1835008, 3),
            new Vc3Compression(1271, true, 0, 0, false, false, 1 << 10 | 1 << 12, false, 0, 1, 32, 917504, 3),
            new Vc3Compression(1272, true, 0, 0, false, false, 1 << 8, false, 2, 3, 32, 917504, 3),
            new Vc3Compression(1273, true, 0, 0, false, false, 1 << 8, false, 1, 2, 32, 606208, 3),
            new Vc3Compression(1274, true, 0, 0, false, false, 1 << 8, false, 1, 2, 32, 188416, 3));

    /**
     * Describes an HD-profile compression ID.
     *
     * @param cid          the compression ID
     * @param width        the samples per line
     * @param lines        the active lines of a coding unit
     * @param interlaced   whether the raster is interlaced
     * @param fieldEncoded whether frames are coded as two fields
     * @param depth        the sample bit depth
     * @param chroma444    whether the ID codes 4:4:4 RGB
     * @param codeSet      the set of codes
     * @param weights      the weight table
     * @param p            the inverse quantization parameter
     * @param frameSize    the compressed frame size
     * @param version      the header version number
     * @return the description
     */
    private static Vc3Compression hd(int cid, int width, int lines, boolean interlaced, boolean fieldEncoded, int depth,
                                     boolean chroma444, int codeSet, int weights, int p, int frameSize, int version) {
        return new Vc3Compression(cid, false, width, lines, interlaced, fieldEncoded, 1 << depth, chroma444, codeSet,
                weights, p, frameSize, version);
    }

    /**
     * Returns the description of a compression ID.
     *
     * @param cid the compression ID
     * @return the description, or empty for an unknown ID
     */
    public static Optional<Vc3Compression> of(int cid) {
        for (var compression : ALL) {
            if (compression.cid == cid) {
                return Optional.of(compression);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns whether the ID permits a sample bit depth.
     *
     * @param depth the bit depth
     * @return {@code true} when permitted
     */
    public boolean permits(int depth) {
        return depth >= 8 && depth <= 12 && (depths & (1 << depth)) != 0;
    }

    /**
     * Returns whether a coding unit of the ID may carry an alpha channel.
     *
     * <p>ST 2019-1 clause 7.2.2, as amended by Amendment 1:2023, permits the alpha flag
     * {@code ALP} for every ID but 1253 and 1274; HD-profile IDs signal it with header version 4.
     *
     * @return {@code true} when alpha is permitted
     */
    public boolean permitsAlpha() {
        return cid != 1253 && cid != 1274;
    }

    /**
     * Returns whether the alpha channel of the ID may be coded losslessly, with the differential
     * run-length code of ST 2019-1 clause 7.3.1.2 ({@code LLA} set).
     *
     * @return {@code true} for IDs 1256 and 1270
     */
    public boolean permitsLosslessAlpha() {
        return cid == 1256 || cid == 1270;
    }

    /**
     * Returns whether the ID may code 4:2:0 Y'CbCr: the sub sampling control {@code SSC = 01b} is
     * valid for header versions above 2 only (clause 7.2.5), and Table C.2 lists 4:2:0 for IDs
     * 1271 to 1274.
     *
     * @return {@code true} for the 4:2:2 RI IDs
     */
    public boolean permits420() {
        return resolutionIndependent && !chroma444;
    }

    /**
     * Returns the quantization weight table of the alpha channel's DCT blocks.
     *
     * <p>Every compression ID quantizes alpha with its luma weights (clause 6.3), so IDs 1271, 1272
     * and 1273 use Tables D.1, D.3 and D.2 and ID 1270 uses Table D.11.
     *
     * @return the table of Annex D, from 1 to 11
     */
    public int alphaWeights() {
        // Ambiguous: ST 2019-1 clause 6.3 says DCT alpha uses "the same quantization matrix as defined
        // for the Y component" (clause 8.1.2 decodes it like Y), while the title of Table D.11 assigns
        // D.11 to the alpha of IDs 1271, 1272 and 1273, whose luma tables are D.1, D.3 and D.2. Smalt
        // follows the prose, which the precedence rule of Amendment 1:2023 clause 2 ranks above
        // tables; for ID 1270 and the HD IDs both readings agree.
        return weights;
    }

    /**
     * Returns the number of coding units of a frame.
     *
     * @return {@code 2} for field-encoded IDs, {@code 1} otherwise
     */
    public int codingUnits() {
        return fieldEncoded ? 2 : 1;
    }

    /**
     * Returns the size of one coding unit of a raster.
     *
     * <p>HD IDs have the fixed size of Table C.1, multiplied by 1.5 with alpha (Amendment 1:2023);
     * RI IDs scale {@code (1 + fA) C0} by the macroblock count with equation 7.1, {@code fA} being
     * 0.5 with alpha and 0 without, rounded to a multiple of 4096 bytes and at least 8192 bytes.
     *
     * @param widthInMb  the raster width in macroblocks
     * @param heightInMb the coding unit height in macroblocks
     * @param alpha      whether an alpha channel is present
     * @return the coding unit size in bytes
     */
    public int codingUnitSize(int widthInMb, int heightInMb, boolean alpha) {
        if (!resolutionIndependent) {
            var size = (long) frameSize / codingUnits();
            return (int) (alpha ? size * 3 / 2 : size);
        }
        var size = (long) frameSize * widthInMb * heightInMb * (alpha ? 3 : 2) / (2L * REFERENCE_MACROBLOCKS);
        var remainder = size % 4096;
        size = remainder >= 2048 ? size + 4096 - remainder : size - remainder;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(8192, size));
    }
}
