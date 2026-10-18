package com.github.auties00.smalt.video.dnxhd.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.ByteArrays;

/**
 * Holds the header of a VC-3 coding unit (SMPTE ST 2019-1 clause 7.2) and reads and writes it.
 *
 * @param headerSize the header size {@code HS} in bytes, where the compressed payload starts
 * @param version the header version number {@code HVN}
 * @param fieldCount the field/frame count {@code FFC}: 1 for a frame, 2 and 3 for the first
 * and second field
 * @param variableRate whether the bitstream is variable-rate ({@code VBR})
 * @param adaptive whether macroblocks may switch to field mode ({@code MACF})
 * @param alpha whether an alpha channel follows the video ({@code ALP})
 * @param losslessAlpha whether the alpha channel is run-length coded ({@code LLA})
 * @param premultipliedAlpha whether the alpha channel has been applied to the video channels
 * ({@code PMA})
 * @param lines the active lines of the coding unit ({@code ALPF})
 * @param width the samples per line: {@code SPL} for the RI profile, the samples per line
 * of the compression ID (Table C.1) for the HD profile
 * @param depth the sample bit depth ({@code SBD}): 8, 10 or 12
 * @param interlaced whether the source is interlaced ({@code SST})
 * @param compression the compression ID
 * @param subsampling the sub sampling control {@code SSC}: 0 for 4:2:2, 1 for 4:2:0, 2 for 4:4:4
 * @param colorVolume the color volume {@code CLV}
 * @param colorFormat the color format flag {@code CLF} as stored
 * @param aspectNumerator the pixel aspect ratio numerator {@code PARC}, or 0
 * @param aspectDenominator the pixel aspect ratio denominator {@code PARN}, or 0
 * @param rows the number of macroblock scan lines {@code Ns}
 * @param rowOffsets the offset of every macroblock scan line from the start of the payload
 */
public record Vc3Header(int headerSize, int version, int fieldCount, boolean variableRate, boolean adaptive,
                        boolean alpha, boolean losslessAlpha, boolean premultipliedAlpha, int lines, int width,
                        int depth, boolean interlaced,
                        Vc3Compression compression, int subsampling, int colorVolume, int colorFormat,
                        int aspectNumerator, int aspectDenominator, int rows, int[] rowOffsets) {
    /**
     * The offset of the macroblock scan indices.
     */
    public static final int SCAN_INDICES = 0x170;

    /**
     * The header size of rasters of up to 1088 lines.
     */
    public static final int BASE_HEADER_SIZE = 0x280;

    /**
     * The end-of-frame signature of a coding unit without CRC.
     */
    public static final int SIGNATURE = 0x600DC0DE;

    /**
     * The largest number of samples per line or lines of a raster.
     */
    public static final int MAX_DIMENSION = 16384;

    /**
     * The color volume code of an out-of-band color description ({@code CLV = 11b}).
     */
    public static final int OUT_OF_BAND_VOLUME = 3;

    /**
     * Returns whether the RGB color format is signaled.
     *
     * <p>ST 2019-1 clause 7.2.5 defines the color format flag {@code CLF}: {@code 0b} codes the
     * bitstream with the Y'CbCr rules and tables, {@code 1b} with the RGB rules and tables, the
     * latter only for compression IDs 1256 and 1270. An RGB coding unit returns red, green and blue
     * whatever color space each macroblock is coded in (clause 6).
     *
     * @return {@code true} for an RGB coding unit
     */
    public boolean rgb() {
        return compression.chroma444() && colorFormat == 1;
    }

    /**
     * Parses the header of a coding unit.
     *
     * <p>The alpha flags are read as ST 2019-1 Amendment 1:2023 defines them, whatever the header
     * version: a set {@code ALP} on an ID that permits alpha announces an alpha channel, and
     * {@code LLA} selects its lossless coding on IDs 1256 and 1270. 4:2:0 sub sampling is accepted
     * on the IDs of Table C.2 that list it (1271 to 1274).
     *
     * @implNote This implementation lays out the macroblocks of an HD-profile ID with the samples
     * per line of Table C.1 rather than the stored {@code SPL}: thin-raster files of
     * Avid encoders (ID 1260 at 1440 samples, for example) store the 1920-sample display
     * width there.
     * @param data the array holding the coding unit
     * @param offset the index of the coding unit's first byte
     * @param length the number of bytes available from {@code offset}
     * @return the header
     * @throws MediaException.Malformed if the header is truncated or invalid
     * @throws MediaException.Unsupported if the compression ID or header version is unknown
     */
    public static Vc3Header parse(byte[] data, int offset, int length) {
        if (length < BASE_HEADER_SIZE) {
            throw new MediaException.Malformed("VC-3 coding unit of " + length + " bytes is truncated");
        }
        var headerSize = ByteArrays.i32be(data, offset);
        var version = data[offset + 4] & 0xFF;
        if (version < 1 || version > 4) {
            throw new MediaException.Unsupported("dnxhd", "header version " + version);
        }
        var cid = ByteArrays.i32be(data, offset + 0x28);
        var compression = Vc3Compression.of(cid)
                .orElseThrow(() -> new MediaException.Unsupported("dnxhd", "compression ID " + Integer.toUnsignedString(cid)));
        var codingA = data[offset + 5] & 0xFF;
        var codingA2 = data[offset + 6] & 0xFF;
        var codingA3 = data[offset + 7] & 0xFF;
        var lines = ByteArrays.u16be(data, offset + 0x18);
        var width = ByteArrays.u16be(data, offset + 0x1A);
        var aspect = data[offset + 0x1C] & 0xFF;
        var depthCode = (data[offset + 0x21] & 0xFF) >>> 5;
        var interlaced = (data[offset + 0x22] & 0x04) != 0;
        var codingB = data[offset + 0x2C] & 0xFF;
        var depth = switch (depthCode) {
            case 1 -> 8;
            case 2 -> 10;
            case 3 -> 12;
            default -> throw new MediaException.Malformed("invalid VC-3 sample bit depth code " + depthCode);
        };
        if (!compression.permits(depth)) {
            throw new MediaException.Malformed("VC-3 compression ID " + cid + " does not permit " + depth + "-bit samples");
        }
        if (lines < 1 || lines > MAX_DIMENSION || width < 1 || width > MAX_DIMENSION) {
            throw new MediaException.Malformed("invalid VC-3 raster " + width + "x" + lines);
        }
        var rows = (lines + 15) >>> 4;
        var expectedHeader = BASE_HEADER_SIZE + 4 * Math.max(0, rows - 68);
        if (headerSize < expectedHeader || headerSize > length - 4) {
            throw new MediaException.Malformed("invalid VC-3 header size " + Integer.toUnsignedString(headerSize));
        }
        var declaredRows = ByteArrays.u16be(data, offset + 0x16C);
        if (declaredRows != rows) {
            throw new MediaException.Malformed("VC-3 header declares " + declaredRows + " macroblock rows, the raster has "
                    + rows);
        }
        var offsets = new int[rows];
        for (var row = 0; row < rows; row++) {
            offsets[row] = ByteArrays.i32be(data, offset + SCAN_INDICES + 4 * row);
        }
        var subsampling = (codingB >>> 5) & 3;
        if (subsampling == 3) {
            throw new MediaException.Malformed("reserved VC-3 sub sampling control");
        }
        if ((subsampling == 2) != compression.chroma444() || subsampling == 1 && !compression.permits420()) {
            throw new MediaException.Malformed("VC-3 sub sampling does not match compression ID " + cid);
        }
        var alpha = (codingA3 & 1) != 0;
        var losslessAlpha = alpha && (codingA3 & 2) != 0;
        if (alpha && !compression.permitsAlpha() || losslessAlpha && !compression.permitsLosslessAlpha()) {
            throw new MediaException.Malformed("VC-3 compression ID " + cid + " does not permit "
                    + (losslessAlpha ? "lossless alpha" : "alpha"));
        }
        if (!compression.resolutionIndependent()) {
            width = compression.width();
        }
        return new Vc3Header(headerSize, version, codingA & 3, (codingA & 0x10) != 0, (codingA2 & 0x20) != 0,
                alpha, losslessAlpha, alpha && (codingA3 & 4) != 0, lines, width, depth, interlaced, compression,
                subsampling,
                (codingB >>> 1) & 3, codingB & 1, (aspect >>> 2) << 8 | data[offset + 0x1F] & 0xFF,
                (aspect & 3) << 8 | data[offset + 0x20] & 0xFF, rows, offsets);
    }

    /**
     * Writes this header.
     *
     * @param out the destination, zero-filled over the header's size
     * @param offset the index of the coding unit's first byte
     */
    public void write(byte[] out, int offset) {
        ByteArrays.put32be(out, offset, headerSize);
        out[offset + 4] = (byte) version;
        out[offset + 5] = (byte) ((variableRate ? 0x10 : 0) | fieldCount);
        out[offset + 6] = (byte) (0x80 | (adaptive ? 0x20 : 0));
        out[offset + 7] = (byte) (0xA0 | (premultipliedAlpha ? 4 : 0) | (losslessAlpha ? 2 : 0) | (alpha ? 1 : 0));
        ByteArrays.put16be(out, offset + 0x18, lines);
        ByteArrays.put16be(out, offset + 0x1A, width);
        out[offset + 0x1C] = (byte) ((aspectNumerator >>> 8) << 2 | aspectDenominator >>> 8);
        ByteArrays.put16be(out, offset + 0x1D, lines);
        out[offset + 0x1F] = (byte) aspectNumerator;
        out[offset + 0x20] = (byte) aspectDenominator;
        var depthCode = depth == 8 ? 1 : depth == 10 ? 2 : 3;
        out[offset + 0x21] = (byte) (depthCode << 5 | 0x18);
        out[offset + 0x22] = (byte) (0x88 | (interlaced ? 0x04 : 0));
        ByteArrays.put32be(out, offset + 0x28, compression.cid());
        var frameEncoding = fieldCount == 1;
        out[offset + 0x2C] = (byte) ((frameEncoding ? 0x80 : 0) | subsampling << 5 | colorVolume << 1 | colorFormat);
        out[offset + 0x5F] = 1;
        out[offset + 0x167] = 2;
        ByteArrays.put16be(out, offset + 0x16A, rows * 4 + 4);
        ByteArrays.put16be(out, offset + 0x16C, rows);
        out[offset + 0x16F] = 0x10;
        for (var row = 0; row < rows; row++) {
            ByteArrays.put32be(out, offset + SCAN_INDICES + 4 * row, rowOffsets[row]);
        }
    }

    /**
     * Returns the size of the header of a coding unit with a number of macroblock rows.
     *
     * @param rows the macroblock rows
     * @return the header size in bytes
     */
    public static int headerSize(int rows) {
        return BASE_HEADER_SIZE + 4 * Math.max(0, rows - 68);
    }
}
