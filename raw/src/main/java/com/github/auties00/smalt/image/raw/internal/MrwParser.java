package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.raw.CfaPattern;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSource;

import java.util.Arrays;
import java.util.Locale;

/**
 * Parses a Minolta or Konica Minolta MRW file: a header of tagged blocks, then the sensor data,
 * which runs from the end of the header to the end of the file.
 *
 * <p>The file starts with {@code 00 4D 52 4D} ({@code "\0MRM"}) and the big-endian 32-bit length
 * of the header that follows. Each block of the header is a four-byte name, a big-endian 32-bit
 * length and the body:
 * <ul>
 * <li>{@code "\0PRD"}, the picture description: at offset 8 the sensor height and width and at 12
 * the image height and width (big-endian 16-bit values), at 16 the stored and the significant bits
 * of a sample, at 18 the storage method ({@code 0x52}: one sample in each big-endian 16-bit word;
 * {@code 0x59}: 12-bit samples packed most significant bit first, two in three bytes) and at 23
 * the Bayer pattern ({@code 1}: red, green, green, blue from the sensor's top-left photosite;
 * {@code 4}: green, blue, red, green);
 * <li>{@code "\0WBG"}, the as-shot white balance: four scale codes, then four big-endian 16-bit
 * levels in red, green, green, blue order;
 * <li>{@code "\0RIF"}, the shot settings, of which the ISO code at offset 6 is read;
 * <li>{@code "\0TTW"}, a big-endian TIFF structure whose offsets are relative to its own start,
 * holding the camera, the EXIF metadata and a maker note whose tags {@code 0x0088} and
 * {@code 0x0089} give the offset, relative to the structure, and the length of a JPEG preview, or
 * whose tag {@code 0x0081} holds the preview itself;
 * <li>{@code "\0PAD"}, padding.
 * </ul>
 *
 * <p>The rendered image is the area of the image size centered on the sensor. The samples are
 * linear, black at 0 and white at {@code 2^bits - 1}, except on the DiMAGE A2 and DiMAGE 5,
 * whose photosites saturate at 3965. The preview's first byte is stored as another value than
 * {@code 0xFF} and is read as {@code 0xFF}; a preview of another aspect ratio than the image
 * letterboxes it across its whole width. White balance levels whose scale codes differ are
 * ignored; the ISO code is read only when the EXIF metadata has no ISO speed, and the APEX
 * shutter and aperture values stand in for a missing exposure time and f-number.
 *
 * @implNote This implementation follows the block and tag names of the ExifTool tag documentation;
 * the layouts, the crop, the white levels, the damaged preview byte and the letterbox were found by
 * examining CC0 sample files and checked against the values Adobe DNG Converter, run as a black
 * box, stores for them. Storage methods, bit depths and Bayer patterns no sample has shown (the
 * green-first pattern of the DiMAGE A200 among them), and the MRW structure of Sony cameras,
 * raise {@link MediaException.Unsupported} rather than being guessed; models no sample shows keep
 * the white level of their bit depth.
 */
final class MrwParser {
    /**
     * The name of the picture description block, {@code "\0PRD"}.
     */
    private static final int PRD = 0x00505244;

    /**
     * The name of the white balance block, {@code "\0WBG"}.
     */
    private static final int WBG = 0x00574247;

    /**
     * The name of the shot settings block, {@code "\0RIF"}.
     */
    private static final int RIF = 0x00524946;

    /**
     * The name of the TIFF metadata block, {@code "\0TTW"}.
     */
    private static final int TTW = 0x00545457;

    /**
     * The storage method of one sample per 16-bit word.
     */
    private static final int PADDED = 0x52;

    /**
     * The storage method of 12-bit samples packed two in three bytes.
     */
    private static final int PACKED = 0x59;

    /**
     * The longest header read.
     */
    private static final int MAX_HEADER = 16 << 20;

    /**
     * The bytes of sensor data a band of rows reads at a time.
     */
    private static final int CHUNK = 1 << 16;

    /**
     * Prevents instantiation.
     */
    private MrwParser() {
        throw new AssertionError();
    }

    /**
     * Parses the file.
     *
     * @param file the description to fill
     * @throws MediaException.Malformed if the header is corrupt or lacks the picture description
     */
    static void parse(RawFile file) {
        var reader = new TiffReader(file.source, 0, false, file.limits);
        var start = reader.bytes(0, 8);
        var headerLength = ByteArrays.u32be(start, 4);
        if (headerLength < 8 || headerLength > MAX_HEADER) {
            throw new MediaException.Malformed("MRW header of " + headerLength + " bytes");
        }
        var header = reader.bytes(8, headerLength);
        var dataOffset = 8 + headerLength;
        byte[] prd = null;
        byte[] wbg = null;
        byte[] rif = null;
        var ttw = -1L;
        var position = 0;
        while (position + 8 <= header.length) {
            var name = (int) ByteArrays.u32be(header, position);
            var length = ByteArrays.u32be(header, position + 4);
            var body = position + 8;
            if (length > header.length - body) {
                throw new MediaException.Malformed("MRW block of " + length + " bytes overruns the header");
            }
            var size = (int) length;
            switch (name) {
                case PRD -> prd = size >= 24 ? Arrays.copyOfRange(header, body, body + size) : null;
                case WBG -> wbg = size >= 12 ? Arrays.copyOfRange(header, body, body + size) : null;
                case RIF -> rif = size >= 7 ? Arrays.copyOfRange(header, body, body + size) : null;
                case TTW -> ttw = size >= 8 ? 8 + body : -1;
                default -> {
                }
            }
            position = body + size;
        }
        if (prd == null) {
            throw new MediaException.Malformed("MRW file without a picture description block");
        }
        if (ttw > 0) {
            metadata(file, ttw);
        }
        if (file.iso == 0 && rif != null) {
            file.iso = iso(rif[6] & 0xFF);
        }
        if (wbg != null) {
            balance(file, wbg);
        }
        sensor(file, reader, prd, dataOffset);
        var imageWidth = ByteArrays.u16be(prd, 14);
        var imageHeight = ByteArrays.u16be(prd, 12);
        file.previews.replaceAll(preview -> preview.letterboxed(imageWidth, imageHeight));
    }

    /**
     * Reads the camera, the shot, the EXIF block and the previews from the TIFF metadata block.
     *
     * @param file the description to fill
     * @param base the absolute offset of the TIFF structure
     */
    private static void metadata(RawFile file, long base) {
        TiffReader tiff;
        TiffTree tree;
        try {
            tiff = TiffReader.open(file.source, base, file.limits);
            tree = new TiffTree(tiff);
        } catch (MediaException.Malformed exception) {
            // a damaged metadata block leaves the camera and the shot out
            return;
        }
        tree.describe(file);
        var exif = tree.exif;
        if (exif == null) {
            return;
        }
        if (file.exposureTime == 0 && exif.has(37377)) {
            file.exposureTime = StrictMath.pow(2, -exif.doubleValue(37377, 0));
        }
        if (file.fNumber == 0 && exif.has(37378)) {
            file.fNumber = StrictMath.pow(2, exif.doubleValue(37378, 0) / 2);
        }
        var note = exif.valuePosition(TiffTree.MAKER_NOTE);
        if (note < 0 || exif.valueCount(TiffTree.MAKER_NOTE) < 6) {
            return;
        }
        Ifd maker;
        try {
            maker = tiff.directory(note - base);
        } catch (MediaException.Malformed exception) {
            return;
        }
        var offset = maker.longValue(0x0088, 0);
        var length = maker.longValue(0x0089, 0);
        if (offset > 0 && length > 0) {
            TiffTree.addPreview(file, tiff, base + offset, length, true);
        }
        if (maker.has(0x0081)) {
            TiffTree.addPreview(file, tiff, maker.valuePosition(0x0081), maker.valueCount(0x0081), true);
        }
    }

    /**
     * Reads the as-shot white balance.
     *
     * @param file the description to fill
     * @param wbg the white balance block
     */
    private static void balance(RawFile file, byte[] wbg) {
        if (wbg[0] != wbg[1] || wbg[0] != wbg[2] || wbg[0] != wbg[3]) {
            return;
        }
        var red = ByteArrays.u16be(wbg, 4);
        var green = (ByteArrays.u16be(wbg, 6) + ByteArrays.u16be(wbg, 8)) / 2.0;
        var blue = ByteArrays.u16be(wbg, 10);
        if (red > 0 && green > 0 && blue > 0) {
            file.color.asShotNeutral = new double[]{green / red, 1, green / blue};
        }
    }

    /**
     * Returns the ISO speed of a shot settings code.
     *
     * @param code the ISO code of the shot settings block
     * @return the ISO speed, or {@code 0} for automatic or an unknown code
     */
    private static long iso(int code) {
        return switch (code) {
            case 48 -> 100;
            case 56 -> 200;
            case 64 -> 400;
            case 72 -> 800;
            case 80 -> 1600;
            case 174 -> 80;
            case 184 -> 200;
            default -> 0;
        };
    }

    /**
     * Describes the sensor image from the picture description.
     *
     * @param file the description to fill
     * @param reader any reader over the file
     * @param prd the picture description block
     * @param dataOffset the absolute offset of the sensor data
     */
    private static void sensor(RawFile file, TiffReader reader, byte[] prd, long dataOffset) {
        var sensorHeight = ByteArrays.u16be(prd, 8);
        var sensorWidth = ByteArrays.u16be(prd, 10);
        var imageHeight = ByteArrays.u16be(prd, 12);
        var imageWidth = ByteArrays.u16be(prd, 14);
        var stored = prd[16] & 0xFF;
        var bits = prd[17] & 0xFF;
        var storage = prd[18] & 0xFF;
        var pattern = prd[23] & 0xFF;
        if (sensorWidth == 0 || sensorHeight == 0 || imageWidth > sensorWidth || imageHeight > sensorHeight) {
            throw new MediaException.Malformed("invalid MRW sensor of " + sensorWidth + "x" + sensorHeight
                    + " for an image of " + imageWidth + "x" + imageHeight);
        }
        if (file.make != null && file.make.toUpperCase(Locale.ROOT).startsWith("SONY")) {
            // TODO: decode the MRW structure Sony cameras write once a CC0 sample shows its layout
            file.unsupported = "Sony MRW files are not supported";
            return;
        }
        var packed = storage == PACKED && stored == 12 && bits == 12 && sensorWidth % 2 == 0;
        var padded = storage == PADDED && stored == 16 && bits == 12;
        if (!packed && !padded) {
            file.unsupported = "MRW storage method 0x" + Integer.toHexString(storage) + " of " + stored + "-bit samples"
                    + " with " + bits + " significant bits is not supported";
            return;
        }
        if (pattern != 1) {
            file.unsupported = "MRW Bayer pattern " + pattern + " is not supported";
            return;
        }
        LimitChecks.checkPicture(file.limits, sensorWidth, sensorHeight);
        var sensor = new Sensor();
        sensor.width = sensorWidth;
        sensor.height = sensorHeight;
        sensor.bitsPerSample = bits;
        sensor.compression = "uncompressed";
        sensor.cfa = CfaPattern.bayer("RGGB");
        sensor.white = new double[]{white(file.model, bits)};
        if (imageWidth > 0 && imageHeight > 0) {
            sensor.defaultCrop = new double[]{(sensorWidth - imageWidth) / 2, (sensorHeight - imageHeight) / 2,
                    imageWidth, imageHeight};
        }
        var source = reader.source();
        var rowBytes = packed ? sensorWidth / 2 * 3 : sensorWidth * 2;
        sensor.reader = (plane, limits) -> read(source, dataOffset, rowBytes, packed, bits, plane);
        file.sensor = sensor;
    }

    /**
     * Returns the white level of a camera model.
     *
     * @param model the camera model, or {@code null}
     * @param bits the significant bits of a sample
     * @return the white level
     */
    private static int white(String model, int bits) {
        if ("DiMAGE A2".equals(model) || "DiMAGE 5".equals(model)) {
            return 3965;
        }
        return (1 << bits) - 1;
    }

    /**
     * Reads the sensor data into a plane, bands of rows in parallel; rows past the end of the file
     * are left at zero.
     *
     * @param source the raw file
     * @param offset the absolute offset of the sensor data
     * @param rowBytes the bytes of one stored row
     * @param packed whether samples are packed two in three bytes rather than one per word
     * @param bits the significant bits of a sample
     * @param plane the plane to fill
     */
    private static void read(MediaSource source, long offset, int rowBytes, boolean packed, int bits,
                             SensorPlane plane) {
        var width = plane.width;
        var height = plane.height;
        var out = plane.integers;
        var bands = ParallelBands.bands(height, (long) width * height);
        ParallelBands.run(bands, band -> {
            var first = ParallelBands.start(band, bands, height);
            var last = ParallelBands.start(band + 1, bands, height);
            var chunkRows = Math.max(1, Math.min(last - first, CHUNK / rowBytes));
            var buffer = new byte[chunkRows * rowBytes];
            for (var y = first; y < last; y += chunkRows) {
                var rows = Math.min(chunkRows, last - y);
                var read = SourceBytes.readAvailable(source, offset + (long) y * rowBytes, buffer, 0, rows * rowBytes);
                for (var row = 0; row < rows; row++) {
                    var start = row * rowBytes;
                    if (start >= read) {
                        return;
                    }
                    var end = Math.min(read, start + rowBytes);
                    if (packed) {
                        Unpack.msbFirst12(buffer, start, end, out, (y + row) * width, width);
                    } else {
                        Unpack.words(buffer, start, end, false, bits, out, (y + row) * width, width);
                    }
                }
            }
        });
    }
}
