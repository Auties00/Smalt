/* Ports X3F tools (x3f_process.c: get_black_level, x3f_get_gain, x3f_get_bmt_to_xyz, get_conv, preprocess_data;
   x3f_image.c: x3f_transform_rect_to_keep_image; x3f_meta.c: x3f_get_max_raw, x3f_get_wb, x3f_get_camf_matrix_for_wb;
   x3f_io.c: x3f_load_true, x3f_load_huffman, x3f_get_raw) at commit e8b48ca. Copyright 2015 Roland and Erik
   Karlsson; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Parses a Sigma X3F file: the {@code FOVb} container of a Foveon sensor's raw image, its embedded
 * previews, its property list and its camera metadata block.
 *
 * <p>The file starts with {@code "FOVb"} and its format version; versions before 4.0 then hold the
 * image size, the rotation and, from 2.1, the white balance setting's name at offset 40. The last
 * four bytes give the offset of the directory: {@code "SECd"}, a version, the entry count and, per
 * entry, the offset, length and type of a section. A section starts with its own identifier:
 * {@code "SECp"} a property list of UTF-16 name and value pairs, {@code "SECi"} an image (a 28-byte
 * header of type, format, columns, rows and row size; type 2 is a preview: format 18, and on
 * Quattro cameras format 25, a JPEG stream, format 3 rows of 8-bit RGB pixels, format 11 Huffman
 * coded RGB pixels), {@code "SECc"} the camera metadata block ({@link X3fCamf}).
 *
 * <p>The sensor image is three layers per photosite, bottom, middle and top, read as linear raw
 * data of three samples per pixel ({@link X3fDecoder} gives the codings). A Quattro sensor's
 * bottom and middle layers have half the top layer's columns and rows; they are brought to the top
 * layer's resolution ({@link X3fQuattro}). The metadata block's {@code ActiveImageArea} (inside
 * {@code KeepImageArea}, which the decoded image covers) is the rendered area. The black level is
 * the mean of the dark shield rows ({@code DarkShieldTop} and {@code DarkShieldBottom}, the latter
 * ignored on the DP2 and the sd Quattro H, whose firmware misplaces it), or of the two column ranges
 * of {@code DarkShieldColRange} when the block has no shield row, measured when the image is read;
 * a Quattro top layer is measured at its own resolution. The white level is
 * {@code 2^ImageDepth - 1}, or {@code RawSaturationLevel} for a TRUE engine camera (one whose block
 * has white balance gains and color corrections), or {@code SaturationLevel}.
 *
 * <p>The as-shot white balance names entries of the metadata block's lists; its name is the one the
 * block's {@code WhiteBalance} code gives, else the header's, else the property list's
 * {@code WB_DESC}. A TRUE engine camera's {@code WhiteBalanceGains} and
 * {@code WhiteBalanceColorCorrections} give the channel gains and the matrix from balanced values
 * to linear sRGB; an older camera's {@code WhiteBalanceIlluminants} and
 * {@code WhiteBalanceCorrections} give a camera to XYZ matrix whose response to D65 is the neutral.
 * The gains are multiplied by {@code SensorAdjustmentGainFact}, {@code TempGainFact} and
 * {@code FNumberGainFact} when present, and the exposure is raised by the ratio of
 * {@code CaptureISO} to {@code SensorISO}. The camera, the shot and the orientation come from the
 * EXIF segment of the largest JPEG preview, then from the property list. The bad pixels and the
 * lens shading of {@link X3fCorrections} are applied when the decoder applies the file's
 * corrections.
 *
 * @implNote This implementation follows Sigma's published FOVb format specification 1.2 for the
 * container, the property list and the previews, and ports the X3F tools library (BSD 3-Clause,
 * license checked) for the metadata block, the raw codings, the black and white levels and the
 * color conversion. Adobe DNG Converter 18.0, run as a black box on the CC0 samples it converts
 * (DP1, SD14, SD15), confirms the rendered area: its linear DNG covers exactly
 * {@code ActiveImageArea}. It stores denoised, color-converted values, not the layers, so it is no
 * reference for them; the decoded layers are bit-identical to the X3F tools library's. Unlike
 * that library, this implementation leaves the column ranges out of the black level when shield
 * rows exist: on the Merrill samples the right column range reads about twice the level of the
 * other shields, and the black of the shield rows is the one that reproduces the balance of the
 * camera's own JPEG preview.
 */
final class X3fParser {
    /**
     * The section identifier of the directory, {@code "SECd"} read little-endian.
     */
    private static final long SECD = 0x64434553L;

    /**
     * The section identifier of a property list, {@code "SECp"}.
     */
    private static final long SECP = 0x70434553L;

    /**
     * The section identifier of an image, {@code "SECi"}.
     */
    private static final long SECI = 0x69434553L;

    /**
     * The section identifier of the camera metadata block, {@code "SECc"}.
     */
    private static final long SECC = 0x63434553L;

    /**
     * The image type and format of a JPEG preview.
     */
    private static final int JPEG = 0x00020012;

    /**
     * The image type and format of a Quattro camera's JPEG thumbnail.
     */
    private static final int QUATTRO_JPEG = 0x00020019;

    /**
     * The image type and format of an uncompressed RGB thumbnail.
     */
    private static final int PIXMAP = 0x00020003;

    /**
     * The image type and format of a Huffman coded RGB thumbnail.
     */
    private static final int HUFFMAN_PIXMAP = 0x0002000B;

    /**
     * The image type and format of the TRUE coding of DP and SD14 to SD15 era cameras.
     */
    private static final int TRUE = 0x0003001E;

    /**
     * The image type and format of the TRUE coding of Merrill cameras.
     */
    private static final int MERRILL = 0x0001001E;

    /**
     * The image type and format of the TRUE coding of the dp Quattro cameras.
     */
    private static final int QUATTRO = 0x00010023;

    /**
     * The image type and format of the TRUE coding of the sd Quattro.
     */
    private static final int SD_QUATTRO = 0x00010025;

    /**
     * The image type and format of the TRUE coding of the sd Quattro H.
     */
    private static final int SD_QUATTRO_H = 0x00010027;

    /**
     * The image type and format of the older Huffman coding.
     */
    private static final int HUFFMAN = 0x00030006;

    /**
     * The image type and format of the Polaroid x530's older Huffman coding.
     */
    private static final int X530 = 0x00030005;

    /**
     * The most directory entries read.
     */
    private static final int MAX_SECTIONS = 1024;

    /**
     * The largest property list read, in bytes.
     */
    private static final int MAX_PROPERTIES = 1 << 20;

    /**
     * The largest metadata block read, in bytes.
     */
    private static final int MAX_CAMF = 64 << 20;

    /**
     * The bytes of a raw section's header read to find its layout.
     */
    private static final int LAYOUT_BYTES = 4096;

    /**
     * The focal length assumed for the lens shading when the file gives none, in millimeters.
     */
    private static final double DEFAULT_FOCAL_LENGTH = 30;

    /**
     * The white balance setting names of the metadata block's {@code WhiteBalance} codes, from 1.
     */
    private static final String[] BALANCES = {"Auto", "Sunlight", "Shadow", "Overcast", "Incandescent", "Florescent",
            "Flash", "Custom", null, null, "ColorTemp", "AutoLSP"};

    /**
     * The D65 white, in XYZ.
     */
    private static final double[] D65_XYZ = {0.95047, 1.0, 1.08883};

    /**
     * The capture time format of EXIF.
     */
    private static final DateTimeFormatter EXIF_TIME = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");

    /**
     * The codings of the raw image.
     */
    private enum Coding {
        /**
         * The TRUE coding, all layers at the image size.
         */
        TRUE,

        /**
         * The TRUE coding of a Quattro sensor, the lower layers at half the top layer's size.
         */
        QUATTRO,

        /**
         * The older Huffman coding.
         */
        HUFFMAN,

        /**
         * The older coding stored without Huffman codes.
         */
        UNCOMPRESSED
    }

    /**
     * A section of the directory.
     *
     * @param offset the absolute offset
     * @param length the length in bytes
     * @param header the first bytes, up to 28
     */
    private record Section(long offset, long length, byte[] header) {
        /**
         * {@return the section identifier, little-endian}
         */
        long identifier() {
            return ByteArrays.u32(header, 0, true);
        }

        /**
         * {@return the image type above the image format, or 0 for a section that is not an
         * image}
         */
        int typeFormat() {
            if (identifier() != SECI || header.length < 28) {
                return 0;
            }
            return (int) (ByteArrays.u32(header, 8, true) << 16 | ByteArrays.u32(header, 12, true));
        }

        /**
         * Returns a 32-bit field of the header.
         *
         * @param position the offset of the field
         * @return the value
         */
        long field(int position) {
            return ByteArrays.u32(header, position, true);
        }
    }

    /**
     * Prevents instantiation.
     */
    private X3fParser() {
        throw new AssertionError();
    }

    /**
     * Parses the file.
     *
     * @param file the description to fill
     * @throws MediaException.Malformed if the header or the directory is corrupt
     */
    static void parse(RawFile file) {
        var source = file.source;
        var reader = new TiffReader(source, 0, true, file.limits);
        var end = RawFile.length(source);
        if (end < 48) {
            throw new MediaException.Malformed("X3F file of " + end + " bytes");
        }
        var head = reader.bytes(0, 72);
        if (head[0] != 'F' || head[1] != 'O' || head[2] != 'V' || head[3] != 'b') {
            throw new MediaException.Malformed("missing X3F header");
        }
        var version = ByteArrays.u32(head, 4, true);
        var directory = reader.u32(end - 4);
        if (directory < 8 || directory > end - 16) {
            throw new MediaException.Malformed("X3F directory offset " + directory + " out of range");
        }
        var directoryHeader = reader.bytes(directory, 12);
        var count = ByteArrays.u32(directoryHeader, 8, true);
        if (ByteArrays.u32(directoryHeader, 0, true) != SECD || count > MAX_SECTIONS
                || directory + 12 + 12 * count > end) {
            throw new MediaException.Malformed("corrupt X3F directory");
        }
        var entries = reader.bytes(directory + 12, 12 * count);
        Section raw = null;
        Section camf = null;
        Section largest = null;
        var properties = new HashMap<String, String>();
        for (var index = 0; index < count; index++) {
            var offset = ByteArrays.u32(entries, 12 * index, true);
            var length = ByteArrays.u32(entries, 12 * index + 4, true);
            if (length < 8 || offset > end - length) {
                continue;
            }
            var section = new Section(offset, length, reader.bytes(offset, Math.min(length, 28)));
            var identifier = section.identifier();
            if (identifier == SECP) {
                properties(reader, section, properties);
            } else if (identifier == SECC && camf == null) {
                camf = section;
            } else if (identifier == SECI && section.header.length == 28) {
                var typeFormat = section.typeFormat();
                if (typeFormat == JPEG || typeFormat == QUATTRO_JPEG) {
                    var previews = file.previews.size();
                    TiffTree.addPreview(file, reader, offset + 28, length - 28);
                    if (file.previews.size() > previews && (largest == null
                            || section.field(16) * section.field(20) > largest.field(16) * largest.field(20))) {
                        largest = section;
                    }
                } else if (typeFormat == PIXMAP || typeFormat == HUFFMAN_PIXMAP) {
                    pixmap(file, section);
                } else if (typeFormat >>> 16 != 2 && raw == null) {
                    raw = section;
                }
            }
        }
        if (largest != null) {
            var tree = TiffTree.ofJpegExif(reader, largest.offset + 28, largest.length - 28);
            if (tree != null) {
                var previews = new ArrayList<>(file.previews);
                tree.describe(file);
                file.previews.clear();
                file.previews.addAll(previews);
            }
        }
        describe(file, properties);
        var metadata = camf != null ? camf(reader, camf, file) : null;
        if (raw == null) {
            return;
        }
        var balance = metadata != null ? balance(metadata) : null;
        if (balance == null) {
            balance = version >= 0x20001 && version < 0x40000 ? string(head, 40, 32) : null;
        }
        if (balance == null || balance.isEmpty()) {
            balance = properties.getOrDefault("WB_DESC", "Auto");
        }
        var focalLength = number(properties.get("FLENGTH"));
        if (focalLength == 0) {
            focalLength = file.focalLength > 0 ? file.focalLength : DEFAULT_FOCAL_LENGTH;
        }
        sensor(file, reader, raw, metadata, properties.get("CAMMODEL"), balance, focalLength);
    }

    /**
     * Returns the name of the white balance setting the metadata block's {@code WhiteBalance} code
     * gives, as Quattro cameras record it.
     *
     * @param camf the metadata block
     * @return the name, {@code "Auto"} for an unknown code, or {@code null} when the block has no
     * code
     */
    private static String balance(X3fCamf camf) {
        var code = X3fCorrections.unsigned(camf, "WhiteBalance", 1);
        if (code == null) {
            return null;
        }
        var index = (long) code[0] - 1;
        return index >= 0 && index < BALANCES.length && BALANCES[(int) index] != null ? BALANCES[(int) index] : "Auto";
    }

    /**
     * Records an RGB thumbnail as a preview when its header describes rows that fit its section.
     *
     * @param file the description to fill
     * @param section the thumbnail's section
     */
    private static void pixmap(RawFile file, Section section) {
        var width = section.field(16);
        var height = section.field(20);
        var rowSize = section.field(24);
        var length = section.length - 28;
        if (width <= 0 || height <= 0 || width * height > Integer.MAX_VALUE / 4) {
            return;
        }
        var columns = (int) width;
        var rows = (int) height;
        var source = file.source;
        var offset = section.offset;
        RawFile.Pixels pixels;
        if (section.typeFormat() == PIXMAP) {
            if (rowSize < 3 * width || (height - 1) * rowSize + 3 * width > length) {
                return;
            }
            pixels = limits -> {
                var size = (long) (rows - 1) * rowSize + 3L * columns;
                LimitChecks.checkAllocation(limits, size + 3L * columns * rows);
                var body = new byte[(int) size];
                read(source, offset + 28, body);
                var rgb = new byte[3 * columns * rows];
                for (var row = 0; row < rows; row++) {
                    System.arraycopy(body, (int) (row * rowSize), rgb, 3 * columns * row, 3 * columns);
                }
                return rgb;
            };
        } else {
            if (rowSize != 0 || length < 1024 + 4 * height || section.length > Integer.MAX_VALUE - 16) {
                return;
            }
            pixels = limits -> {
                LimitChecks.checkAllocation(limits, section.length + 3L * columns * rows);
                var body = new byte[(int) section.length];
                read(source, offset, body);
                return X3fDecoder.decodeThumbnail(body, columns, rows);
            };
        }
        file.previews.add(new RawFile.Preview(offset + 28, length, columns, rows, pixels));
    }

    /**
     * Reads a property list section into a map; a later name replaces an earlier one.
     *
     * @param reader any reader over the file
     * @param section the property list section
     * @param properties the map to fill
     */
    private static void properties(TiffReader reader, Section section, Map<String, String> properties) {
        if (section.length > MAX_PROPERTIES || section.header.length < 24) {
            return;
        }
        var body = reader.bytes(section.offset, section.length);
        var count = ByteArrays.u32(body, 8, true);
        var characters = 24 + 8 * count;
        if (count > (body.length - 24) / 8) {
            return;
        }
        for (var index = 0; index < count; index++) {
            var name = utf16(body, characters + 2 * ByteArrays.u32(body, 24 + 8 * (int) index, true));
            var value = utf16(body, characters + 2 * ByteArrays.u32(body, 28 + 8 * (int) index, true));
            if (name != null && value != null) {
                properties.put(name, value.strip());
            }
        }
    }

    /**
     * Reads a NUL-terminated UTF-16LE string.
     *
     * @param data the bytes
     * @param start the index of the first character
     * @return the string, or {@code null} when it starts outside the data
     */
    private static String utf16(byte[] data, long start) {
        if (start < 0 || start >= data.length) {
            return null;
        }
        var stop = (int) start;
        while (stop + 1 < data.length && (data[stop] != 0 || data[stop + 1] != 0)) {
            stop += 2;
        }
        return new String(data, (int) start, stop - (int) start, StandardCharsets.UTF_16LE);
    }

    /**
     * Reads a NUL-terminated ISO 8859-1 string of a fixed field.
     *
     * @param data the bytes
     * @param start the index of the field
     * @param length the length of the field
     * @return the string, stripped
     */
    private static String string(byte[] data, int start, int length) {
        var stop = start;
        while (stop < start + length && data[stop] != 0) {
            stop++;
        }
        return new String(data, start, stop - start, StandardCharsets.ISO_8859_1).strip();
    }

    /**
     * Fills the camera and the shot the EXIF segment left unknown from the property list.
     *
     * @param file the description to fill
     * @param properties the property list
     */
    private static void describe(RawFile file, Map<String, String> properties) {
        if (file.make == null) {
            file.make = properties.get("CAMMANUF");
        }
        if (file.model == null) {
            file.model = properties.get("CAMMODEL");
        }
        if (file.iso == 0) {
            file.iso = (long) number(properties.get("ISO"));
        }
        if (file.exposureTime == 0) {
            var shutter = number(properties.get("SHUTTER"));
            file.exposureTime = shutter > 0 ? shutter : number(properties.get("EXPTIME")) / 1e6;
        }
        if (file.fNumber == 0) {
            file.fNumber = number(properties.get("APERTURE"));
        }
        if (file.focalLength == 0) {
            file.focalLength = number(properties.get("FLENGTH"));
        }
        var time = (long) number(properties.get("TIME"));
        if (file.captureTime == null && time > 0) {
            file.captureTime = EXIF_TIME.format(Instant.ofEpochSecond(time).atOffset(ZoneOffset.UTC));
        }
    }

    /**
     * Parses a property value as a number.
     *
     * @param value the value, or {@code null}
     * @return the number, or 0 when the value is missing, not a number or not positive
     */
    private static double number(String value) {
        if (value == null) {
            return 0;
        }
        try {
            var number = Double.parseDouble(value.strip());
            return number > 0 && Double.isFinite(number) ? number : 0;
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    /**
     * Reads and decodes the camera metadata block.
     *
     * @param reader any reader over the file
     * @param section the block's section
     * @param file the description, for its limits
     * @return the block, or {@code null} when it is too large, of an unknown type or corrupt
     */
    private static X3fCamf camf(TiffReader reader, Section section, RawFile file) {
        if (section.length > MAX_CAMF || section.header.length < 28) {
            return null;
        }
        LimitChecks.checkAllocation(file.limits, section.length);
        var data = reader.bytes(section.offset + 28, section.length - 28);
        var header = new long[]{section.field(12), section.field(16), section.field(20), section.field(24)};
        try {
            return X3fCamf.decode((int) section.field(8), header, data);
        } catch (MediaException.Malformed exception) {
            // a damaged metadata block leaves the levels and colors at their defaults
            return null;
        }
    }

    /**
     * Describes the sensor image.
     *
     * @param file the description to fill
     * @param reader any reader over the file
     * @param raw the raw image section
     * @param camf the metadata block, or {@code null}
     * @param model the camera model of the property list, or {@code null}
     * @param balance the name of the as-shot white balance setting
     * @param focalLength the focal length of the shot in millimeters
     * @throws MediaException.Malformed if the raw image header is corrupt
     */
    private static void sensor(RawFile file, TiffReader reader, Section raw, X3fCamf camf, String model,
                               String balance, double focalLength) {
        var typeFormat = raw.typeFormat();
        Coding coding;
        switch (typeFormat) {
            case TRUE, MERRILL -> coding = Coding.TRUE;
            case QUATTRO, SD_QUATTRO, SD_QUATTRO_H -> coding = Coding.QUATTRO;
            case HUFFMAN, X530 -> coding = raw.field(24) != 0 ? Coding.UNCOMPRESSED : Coding.HUFFMAN;
            default -> {
                file.unsupported = "X3F raw image type " + (typeFormat >>> 16) + " format " + (typeFormat & 0xFFFF)
                        + " is not supported";
                return;
            }
        }
        var width = (int) Math.min(raw.field(16), Integer.MAX_VALUE);
        var height = (int) Math.min(raw.field(20), Integer.MAX_VALUE);
        if (width <= 0 || height <= 0) {
            throw new MediaException.Malformed("X3F raw image of " + width + "x" + height);
        }
        X3fDecoder.TrueLayout layout = null;
        var layered = false;
        var rowSize = raw.field(24);
        switch (coding) {
            case TRUE, QUATTRO -> {
                layout = X3fDecoder.trueLayout(reader.bytes(raw.offset, Math.min(raw.length, LAYOUT_BYTES)),
                        raw.length, coding == Coding.QUATTRO, width, height);
                var columns = layout.columns();
                var rows = layout.rows();
                if (coding == Coding.QUATTRO && rows[0] == height / 2) {
                    layered = true;
                    if (rows[1] != rows[0] || columns[1] < columns[0] || rows[2] < 2 * rows[0]
                            || columns[2] < 2 * columns[0] || columns[0] == 0 || rows[0] == 0) {
                        throw new MediaException.Malformed("X3F Quattro layers of " + columns[0] + "x" + rows[0] + ", "
                                + columns[1] + "x" + rows[1] + " and " + columns[2] + "x" + rows[2]);
                    }
                    width = 2 * columns[0];
                    height = 2 * rows[0];
                } else {
                    for (var layer = 0; layer < 3; layer++) {
                        if (rows[layer] != height || columns[layer] < width) {
                            throw new MediaException.Malformed("X3F layer of " + columns[layer] + "x" + rows[layer]
                                    + " in an image of " + width + "x" + height);
                        }
                    }
                }
            }
            case HUFFMAN -> {
                if (raw.length < 28 + 2 * 1024 + 4 * 1024 + 4L * height) {
                    throw new MediaException.Malformed("X3F raw section of " + raw.length + " bytes");
                }
            }
            case UNCOMPRESSED -> {
                if (rowSize < 4L * width || 28 + 2 * 1024 + (height - 1) * rowSize + 4L * width > raw.length) {
                    throw new MediaException.Malformed("X3F raw section of " + raw.length + " bytes for rows of "
                            + rowSize + " bytes");
                }
            }
        }
        LimitChecks.checkPicture(file.limits, width, height);
        var sensor = new Sensor();
        sensor.width = width;
        sensor.height = height;
        sensor.samplesPerPixel = 3;
        sensor.compression = switch (coding) {
            case TRUE, QUATTRO -> "Sigma TRUE";
            case HUFFMAN -> "Sigma Huffman";
            case UNCOMPRESSED -> "uncompressed";
        };
        var depth = camf != null ? camf.numbers("ImageDepth", 1) : null;
        sensor.bitsPerSample = depth != null && depth[0] >= 8 && depth[0] <= 16 ? (int) depth[0] : 12;
        var bias = coding == Coding.HUFFMAN ? X3fDecoder.HUFFMAN_BIAS : 0;
        sensor.white = white(camf, sensor.bitsPerSample, depth != null, bias);
        var keep = keep(camf, width, height);
        var active = camf != null ? rectangle(camf.numbers("ActiveImageArea", 4), keep, width, height, !layered)
                : null;
        if (active != null) {
            sensor.activeArea = new int[]{active[1], active[0], active[3] + 1, active[2] + 1};
        }
        var skipBottom = "SIGMA DP2".equals(model) || X3fCorrections.cameraId(camf) == X3fCorrections.SD_QUATTRO_H;
        int[][] shields;
        int[][] topShields = null;
        if (layered) {
            shields = shields(camf, keep, layout.columns()[0], layout.rows()[0], skipBottom, true);
            topShields = shields(camf, keep, layout.columns()[2], layout.rows()[2], skipBottom, false);
        } else {
            shields = shields(camf, keep, width, height, skipBottom, true);
        }
        var gain = color(file, sensor, camf, balance);
        if (camf != null) {
            sensor.opcodes2 = X3fCorrections.gainMap(X3fCorrections.spatialGain(camf, balance, focalLength), width,
                    height, sensor.active());
        }
        sensor.reader = new LayerReader(reader.source(), raw, coding, layout, layered, width, height, rowSize, bias,
                shields, topShields, gain != null ? gain : new double[]{1, 1, 1}, camf, sensor);
        file.sensor = sensor;
    }

    /**
     * Reads the layers of the raw image section, measures their black levels and, when asked,
     * fixes the bad pixels.
     *
     * @param source the file
     * @param raw the raw image section
     * @param coding the coding of the section
     * @param layout the layout of a TRUE section, or {@code null}
     * @param layered whether the section holds Quattro layers of different sizes
     * @param width the image width
     * @param height the image height
     * @param rowSize the row size of an uncompressed section
     * @param bias the value added to every stored sample
     * @param shields the dark shield areas of the image, or of the lower layers of a layered section
     * @param topShields the dark shield areas of the top layer of a layered section, or {@code null}
     * @param gain the white balance gain of each layer
     * @param camf the metadata block, or {@code null}
     * @param sensor the sensor whose black levels are set
     */
    private record LayerReader(MediaSource source, Section raw, Coding coding, X3fDecoder.TrueLayout layout,
                               boolean layered, int width, int height, long rowSize, int bias, int[][] shields,
                               int[][] topShields, double[] gain, X3fCamf camf,
                               Sensor sensor) implements Sensor.Reader {
        @Override
        public void read(SensorPlane plane, MediaLimits limits) {
            read(plane, limits, false);
        }

        @Override
        public void read(SensorPlane plane, MediaLimits limits, boolean corrections) {
            LimitChecks.checkAllocation(limits, raw.length);
            var body = new byte[(int) Math.min(raw.length, Integer.MAX_VALUE - 16)];
            X3fParser.read(source, raw.offset, body);
            if (layered) {
                quattro(body, plane, limits, corrections);
                return;
            }
            switch (coding) {
                case HUFFMAN -> X3fDecoder.decodeHuffman(body, width, height, plane.integers);
                case UNCOMPRESSED -> X3fDecoder.decodeUncompressed(body, width, height, rowSize, plane.integers);
                default -> X3fDecoder.decodeTrue(body, layout, width, height, plane.integers);
            }
            sensor.black = black(plane.integers, width, 3, 3, shields, bias);
            if (corrections && camf != null) {
                X3fCorrections.fixBadPixels(camf, plane.integers, width, height, 3, 3);
            }
        }

        /**
         * Decodes Quattro layers of different sizes and expands them to the top layer's
         * resolution.
         *
         * @param body the section
         * @param plane the plane to fill
         * @param limits the resource bounds
         * @param corrections whether the bad pixels are fixed
         */
        private void quattro(byte[] body, SensorPlane plane, MediaLimits limits, boolean corrections) {
            var lowWidth = layout.columns()[0];
            var lowHeight = layout.rows()[0];
            var topWidth = layout.columns()[2];
            var topHeight = layout.rows()[2];
            LimitChecks.checkAllocation(limits, ((long) lowWidth * lowHeight * 3 + (long) topWidth * topHeight) * 2);
            var low = new short[lowWidth * lowHeight * 3];
            var top = new short[topWidth * topHeight];
            X3fDecoder.decodeQuattro(body, layout, low, top);
            X3fQuattro.bin(low, lowWidth, lowHeight, top, topWidth);
            var lower = black(low, lowWidth, 3, 2, shields, 0);
            var upper = black(top, topWidth, 1, 1, topShields, 0);
            var black = new double[]{lower[0], lower[1], upper[0]};
            if (corrections && camf != null) {
                X3fCorrections.fixBadPixels(camf, top, topWidth, topHeight, 1, 1);
                X3fCorrections.fixBadPixels(camf, low, lowWidth, lowHeight, 3, 3);
            }
            var scale = new double[3];
            for (var layer = 0; layer < 3; layer++) {
                var range = sensor.white[layer] - black[layer];
                scale[layer] = range > 0 && gain[layer] > 0 ? gain[layer] / range : 1;
            }
            X3fQuattro.expand(low, lowWidth, lowHeight, top, topWidth, black, scale, plane.integers);
            sensor.black = black;
        }
    }

    /**
     * Reads bytes of the file, leaving the bytes past its end at zero.
     *
     * @param source the file
     * @param offset the absolute offset
     * @param target the bytes to fill
     */
    private static void read(MediaSource source, long offset, byte[] target) {
        var done = 0;
        while (done < target.length) {
            var read = SourceBytes.readAvailable(source, offset + done, target, done, target.length - done);
            if (read <= 0) {
                return;
            }
            done += read;
        }
    }

    /**
     * Returns the white level of each layer.
     *
     * @param camf the metadata block, or {@code null}
     * @param bits the bits of a sample
     * @param depth whether the block gives the image depth
     * @param bias the value added to every stored sample
     * @return the levels
     */
    private static double[] white(X3fCamf camf, int bits, boolean depth, int bias) {
        var maximum = (1 << bits) - 1.0;
        double[] levels = null;
        if (camf != null && !depth) {
            var trueEngine = (camf.has("WhiteBalanceColorCorrections") || camf.has("DP1_WhiteBalanceColorCorrections"))
                    && (camf.has("WhiteBalanceGains") || camf.has("DP1_WhiteBalanceGains"));
            levels = camf.numbers(trueEngine ? "RawSaturationLevel" : "SaturationLevel", 3);
        }
        var white = new double[3];
        for (var layer = 0; layer < 3; layer++) {
            white[layer] = (levels != null && levels[layer] > 0 ? levels[layer] : maximum) + bias;
        }
        return white;
    }

    /**
     * Returns the area the decoded image covers, in sensor coordinates.
     *
     * @param camf the metadata block, or {@code null}
     * @param width the image width
     * @param height the image height
     * @return the left, top, right and bottom edges, inclusive
     */
    private static long[] keep(X3fCamf camf, int width, int height) {
        var keep = camf != null ? camf.numbers("KeepImageArea", 4) : null;
        if (keep == null || keep[0] > keep[2] || keep[1] > keep[3]) {
            return new long[]{0, 0, width - 1, height - 1};
        }
        return new long[]{(long) keep[0], (long) keep[1], (long) keep[2], (long) keep[3]};
    }

    /**
     * Maps a rectangle in sensor coordinates to an image: clipped to the kept area, translated to
     * its origin and, for an image that covers the kept area at another resolution, scaled to the
     * image size.
     *
     * @param rectangle the left, top, right and bottom edges, inclusive, or {@code null}
     * @param keep the kept area
     * @param width the image width
     * @param height the image height
     * @param scaled whether the image covers the kept area at its own resolution; otherwise the
     * image has the kept area's resolution and its origin, and may be larger
     * @return the left, top, right and bottom edges in the image, inclusive, or {@code null} when
     * the rectangle is missing, inverted or outside the kept area, or the kept area does not fit
     * an unscaled image
     */
    private static int[] rectangle(double[] rectangle, long[] keep, int width, int height, boolean scaled) {
        if (rectangle == null) {
            return null;
        }
        var edges = new long[]{(long) rectangle[0], (long) rectangle[1], (long) rectangle[2], (long) rectangle[3]};
        if (edges[0] > edges[2] || edges[1] > edges[3] || edges[0] > keep[2] || edges[1] > keep[3]
                || edges[2] < keep[0] || edges[3] < keep[1]) {
            return null;
        }
        var columns = keep[2] - keep[0] + 1;
        var rows = keep[3] - keep[1] + 1;
        if (!scaled && (columns > width || rows > height)) {
            return null;
        }
        var result = new int[4];
        for (var index = 0; index < 4; index++) {
            var horizontal = index % 2 == 0;
            var low = horizontal ? keep[0] : keep[1];
            var high = horizontal ? keep[2] : keep[3];
            var value = Math.clamp(edges[index], low, high) - low;
            result[index] = (int) (scaled ? value * (horizontal ? width : height) / (horizontal ? columns : rows) : value);
        }
        return result[2] < width && result[3] < height ? result : null;
    }

    /**
     * Returns the dark shield areas of an image: the top and bottom shields, or the two column
     * ranges when the block has neither.
     *
     * @param camf the metadata block, or {@code null}
     * @param keep the kept area
     * @param width the image width
     * @param height the image height
     * @param skipBottom whether the bottom shield is ignored, as the firmware misplaces it
     * @param scaled whether the image covers the kept area at its own resolution
     * @return the areas, each left, top, right and bottom, inclusive
     */
    private static int[][] shields(X3fCamf camf, long[] keep, int width, int height, boolean skipBottom,
                                   boolean scaled) {
        if (camf == null) {
            return new int[0][];
        }
        var areas = new ArrayList<int[]>();
        var top = rectangle(camf.numbers("DarkShieldTop", 4), keep, width, height, scaled);
        if (top != null) {
            areas.add(top);
        }
        var bottom = skipBottom ? null : rectangle(camf.numbers("DarkShieldBottom", 4), keep, width, height, scaled);
        if (bottom != null) {
            areas.add(bottom);
        }
        var columns = camf.numbers("DarkShieldColRange", 4);
        if (columns != null && areas.isEmpty()) {
            for (var side = 0; side < 2; side++) {
                var area = rectangle(new double[]{columns[2 * side], 0, columns[2 * side + 1], 0xFFFFFFFFL},
                        keep, width, height, scaled);
                if (area != null) {
                    areas.add(area);
                }
            }
        }
        return areas.toArray(int[][]::new);
    }

    /**
     * Measures the black level of the first layers of an image as the mean over the dark shield
     * areas.
     *
     * @param samples the image
     * @param width the image width
     * @param channels the samples of each pixel
     * @param layers the layers measured
     * @param shields the dark shield areas
     * @param bias the value added to every stored sample, the black level without shields
     * @return the black level of each measured layer
     */
    private static double[] black(short[] samples, int width, int channels, int layers, int[][] shields, int bias) {
        var sums = new long[layers];
        var count = 0L;
        for (var area : shields) {
            for (var row = area[1]; row <= area[3]; row++) {
                var index = (row * width + area[0]) * channels;
                for (var column = area[0]; column <= area[2]; column++) {
                    for (var layer = 0; layer < layers; layer++) {
                        sums[layer] += samples[index + layer] & 0xFFFF;
                    }
                    index += channels;
                }
            }
            count += (long) (area[2] - area[0] + 1) * (area[3] - area[1] + 1);
        }
        var black = new double[layers];
        for (var layer = 0; layer < layers; layer++) {
            black[layer] = count > 0 ? (double) sums[layer] / count : bias;
        }
        return black;
    }

    /**
     * Describes the colors from the metadata block: the as-shot neutral, the matrix from balanced
     * values to linear sRGB and the exposure the ISO speed adds.
     *
     * @param file the description to fill
     * @param sensor the sensor, whose balance is estimated when the block has no colors
     * @param camf the metadata block, or {@code null}
     * @param balance the name of the as-shot white balance setting
     * @return the white balance gain of each layer, or {@code null} when the block gives none
     */
    private static double[] color(RawFile file, Sensor sensor, X3fCamf camf, String balance) {
        if (camf == null) {
            sensor.estimateBalance = true;
            return null;
        }
        double[] gain;
        double[] balancedToSrgb;
        var gains = forBalance(camf, "WhiteBalanceGains", balance, 3);
        if (gains == null) {
            gains = forBalance(camf, "DP1_WhiteBalanceGains", balance, 3);
        }
        var corrections = forBalance(camf, "WhiteBalanceColorCorrections", balance, 9);
        if (corrections == null) {
            corrections = forBalance(camf, "DP1_WhiteBalanceColorCorrections", balance, 9);
        }
        var illuminant = forBalance(camf, "WhiteBalanceIlluminants", balance, 9);
        var correction = forBalance(camf, "WhiteBalanceCorrections", balance, 9);
        if (gains != null && corrections != null) {
            gain = gains;
            balancedToSrgb = corrections;
        } else if (illuminant != null && correction != null) {
            var cameraToXyz = ColorMath.multiply(correction, illuminant);
            var xyzToCamera = ColorMath.invert(cameraToXyz);
            if (xyzToCamera == null) {
                sensor.estimateBalance = true;
                return null;
            }
            var neutral = ColorMath.transform(xyzToCamera, D65_XYZ);
            gain = new double[3];
            for (var layer = 0; layer < 3; layer++) {
                if (!(neutral[layer] > 0)) {
                    sensor.estimateBalance = true;
                    return null;
                }
                gain[layer] = 1 / neutral[layer];
            }
            balancedToSrgb = ColorMath.multiply(ColorMath.invert(srgbToXyz()),
                    ColorMath.multiply(cameraToXyz, ColorMath.diagonal(neutral)));
        } else {
            sensor.estimateBalance = true;
            return null;
        }
        gain = gain.clone();
        for (var factor : new String[]{"SensorAdjustmentGainFact", "TempGainFact", "FNumberGainFact"}) {
            var values = camf.numbers(factor, 3);
            if (values != null && values[0] > 0 && values[1] > 0 && values[2] > 0) {
                for (var layer = 0; layer < 3; layer++) {
                    gain[layer] *= values[layer];
                }
            }
        }
        var smallest = Double.MAX_VALUE;
        var neutral = new double[3];
        for (var layer = 0; layer < 3; layer++) {
            if (!(gain[layer] > 0) || !Double.isFinite(gain[layer])) {
                sensor.estimateBalance = true;
                return null;
            }
            neutral[layer] = 1 / gain[layer];
            smallest = Math.min(smallest, gain[layer]);
        }
        file.color.asShotNeutral = neutral;
        file.color.cameraToSrgb = balancedToSrgb.clone();
        var sensorIso = camf.numbers("SensorISO", 1);
        var captureIso = camf.numbers("CaptureISO", 1);
        var scaling = sensorIso != null && captureIso != null && sensorIso[0] > 0 && captureIso[0] > 0
                ? captureIso[0] / sensorIso[0] : 1;
        file.color.baselineExposure = StrictMath.log(scaling * smallest) / StrictMath.log(2);
        return gain;
    }

    /**
     * Returns the matrix a white balance list names for a setting.
     *
     * @param camf the metadata block
     * @param list the name of the property list entry
     * @param balance the name of the white balance setting
     * @param count the number of elements required
     * @return the elements, or {@code null} when the list, the setting or the matrix is missing
     */
    private static double[] forBalance(X3fCamf camf, String list, String balance, int count) {
        var name = camf.property(list, balance);
        if (name == null && "Daylight".equals(balance)) {
            name = camf.property(list, "Sunlight");
        }
        return name != null ? camf.numbers(name, count) : null;
    }

    /**
     * {@return the matrix from linear sRGB to XYZ, both relative to D65}
     */
    private static double[] srgbToXyz() {
        return ColorMath.rgbToXyz(new double[]{0.64, 0.33}, new double[]{0.30, 0.60}, new double[]{0.15, 0.06},
                ColorMath.D65_XY);
    }
}
