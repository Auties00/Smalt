/* Ports arw (arw.go, readraw.go: readCrawBlock, readCRAW). Copyright (c) 2018 Sjon Kosse; MIT License. Modified for
   Smalt. */
package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.CfaPattern;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/**
 * Parses a Sony ARW file: a TIFF/EP structure whose color filter array directory holds the sensor
 * data, either as 16-bit words or with Sony's lossy "cRAW" compression (TIFF compression 32767 at
 * one byte per photosite).
 *
 * <p>cRAW codes each row in groups of 32 photosites, as two 16-byte blocks: the first holds the 16
 * even photosites of the group, the second the 16 odd ones. A block is a 128-bit little-endian
 * field: an 11-bit maximum, an 11-bit minimum, the 4-bit positions of the maximum and the minimum,
 * and 14 seven-bit deltas for the other photosites in order. Deltas are scaled by the smallest
 * power of two, up to 16, for which 128 steps cover the range between minimum and maximum. The
 * 11-bit values are tone-mapped to 14-bit linear values by a five-segment curve whose steps
 * double at the thresholds tag {@code 0x7010} records.
 *
 * <p>Uncompressed data stores one sample in each 16-bit word on the 14-bit scale, whatever bit
 * depth the directory states. The DSLR-A900 instead stores its uncompressed data under compression
 * 32767 at one and a half bytes per photosite: 12-bit samples on the 12-bit scale, packed in pairs
 * into three bytes least significant bits first (the first sample is the first byte and the low
 * half of the second, the second sample the high half of the second byte and the third byte); its
 * untagged black level is a quarter of the 14-bit one (128), its white level 4095 and its image
 * the 6048 by 4032 area 12 photosites from the left and 8 from the top. The DSLR-A700, whose EXIF image size is
 * its whole sensor image too, has the 4272 by 2848 area four photosites from the left and the top.
 *
 * <p>The DSLR-A200, A230, A290, A300, A330, A350, A380 and A390 store their 12-bit data under compression 32767 in the
 * variable-length coding of {@link SonyDeltaCoding}, unless the raw directory's tag {@code 0x7000} names another kind
 * of data, with white level 4095 and the black level of their encrypted {@code SR2} directory
 * ({@link Sr2Balance#black}). So does the DSLR-A100, whose first directory's {@code SubIFDs}
 * tag holds the offset of the sensor data itself, up to the end of the file; its stream has 3881 columns of 2608
 * rows, of which the 3880 by 2600 photosites from the left and the top are the image, green first ({@code GRBG}). Its
 * {@code DNGPrivateData} tag gives the offset of a little-endian {@code "\0MRI"} block, laid out like the header of an
 * MRW file, whose {@code "\0WBG"} block holds its as-shot white balance as four scale codes and four little-endian
 * levels in red, green, green and blue order. These models' image is the area four photosites inside each edge.
 *
 * <p>The levels are those of the raw directory's own tags when it has them:
 * {@code BlackLevel} (tag {@code 0x7310}, four values in red, green, green, blue order) and
 * {@code WB_RGGBLevels} (tag {@code 0x7313}, the as-shot multipliers in the same order); the white
 * level is that of the model, which is the {@code WhiteLevel} tag ({@code 0xC61D}) where the file has
 * one, except on the models whose photosites saturate lower, and 16300 on the models without it.
 * Files without the black level tag take the black level of their model, 512 on the 14-bit scale
 * except 800 on the RX10, RX10 III, RX100 to RX100 V and RX0 compacts; files without the white balance
 * tag read it from the encrypted {@code SR2} directory where its layout is known ({@link Sr2Balance}), and
 * otherwise have it estimated from the camera's embedded preview (see {@link PreviewBalance}). The files whose
 * compression 32767 data is not decoded still report the white balance they record.
 *
 * <p>Two codings are reported unsupported. The lossless compressed RAW of the S and M sizes stores YCbCr samples
 * (4:2:2, chroma centered on 16384) in lossless JPEG tiles; Adobe DNG Converter turns them into linear red, green and
 * blue through a chroma upsampling of its own, which neither the file's tags nor a specification define and which a
 * single CC0 sample (ILCE-9M3) does not pin down exactly. The coding of TIFF compression 32766 (ILCE-7M5) has no
 * specification under a license this project can port from, and the converter does not read the CC0 sample.
 *
 * @implNote This implementation follows Henry Dietz's description of the cRAW format ("Sony ARW2
 * Compression: Artifacts And Credible Repair", Electronic Imaging 2016) and the block
 * layout of the {@code arw} Go package by Sjon Kosse; the tone curve
 * thresholds are read as quarter values on a 12-bit scale indexed by twice the 11-bit
 * value, which makes the curve span the 14-bit range. Sony's lossless compression (TIFF
 * compression 7) goes through the generic lossless JPEG reader; its frames of four components at half the
 * tile's width and height hold a two-by-two cell of photosites per pixel, an order found from the CC0 ILCE-1M2
 * file, in which only this placement gives each color's photosites the smoothness of the scene. The levels of
 * the models without level tags and the white levels were derived from the values Adobe DNG Converter, run as
 * a black box, stores for CC0 sample files of every model of the raw.pixls.us archive, and so was the
 * A900's packing, which reproduces the converter's photosites exactly. So were the variable-length coding, its
 * models' levels and the DSLR-A100's layout. Which files hold the variable-length coding comes from a list of models
 * and tag {@code 0x7000} rather than from the data's size, since a stream can have any size, the sizes of the other
 * codings included; in the CC0 files the tag is 1 for the A900's packing, 2 for cRAW and 3 for this coding. No camera
 * or software is known to write a compressed ARQ file and no sample of one exists: the lossless JPEG files are read as
 * the converter reads copies of the CC0 ILCE-7RM3 ARQ file with sensor data of that compression, as one strip or as
 * tiles, of four components per pixel or of two components at twice the width. With that file's own sensor data in
 * each of these layouts, this parser's values and the converter's stay those of the uncompressed file.
 */
final class ArwParser {
    /**
     * The TIFF compression code of Sony's raw data.
     */
    private static final int SONY_COMPRESSION = 32767;

    /**
     * The black level of the models without the black level tag, on the 14-bit scale.
     */
    private static final int BLACK = 512;

    /**
     * The white level of the models without the white level tag, on the 14-bit scale.
     */
    private static final int WHITE = 16300;

    /**
     * The white level of packed 12-bit data without the white level tag.
     */
    private static final int PACKED_WHITE = 4095;

    /**
     * The photometric interpretation (sequential color filter) of the raw directory of an ARQ file.
     */
    private static final int PHOTOMETRIC_SEQUENTIAL = 32892;

    /**
     * The photometric interpretation (YCbCr) of the raw directory of the lossless compressed RAW at the S and M sizes.
     */
    private static final int PHOTOMETRIC_YCBCR = 6;

    /**
     * The samples of each pixel of an ARQ file.
     */
    private static final int ARQ_SAMPLES = 4;

    /**
     * The most bytes of a lossless JPEG block read to find its frame header.
     */
    private static final int FRAME_HEADER_BYTES = 4096;

    /**
     * The components of a lossless JPEG frame whose pixels each hold a two-by-two cell of photosites.
     */
    private static final int QUAD_COMPONENTS = 4;

    /**
     * The TIFF compression of lossless JPEG.
     */
    private static final int LOSSLESS_JPEG = 7;

    /**
     * The tag of a raw directory that names the kind of its sensor data.
     */
    private static final int SONY_RAW_TYPE = 0x7000;

    /**
     * The value of {@link #SONY_RAW_TYPE} for {@link SonyDeltaCoding}.
     */
    private static final int SONY_RAW_TYPE_DELTA = 3;

    /**
     * The models with a raw directory that write {@link SonyDeltaCoding}.
     */
    private static final Set<String> DELTA_CODED = Set.of("DSLR-A200", "DSLR-A230", "DSLR-A290", "DSLR-A300",
            "DSLR-A330", "DSLR-A350", "DSLR-A380", "DSLR-A390");

    /**
     * The photosites each edge of variable-length coded data has outside the image area.
     */
    private static final int DELTA_BORDER = 4;

    /**
     * The model whose first directory points straight at {@link SonyDeltaCoding} data.
     */
    private static final String A100 = "DSLR-A100";

    /**
     * The columns of the DSLR-A100's stream.
     */
    private static final int A100_STREAM_WIDTH = 3881;

    /**
     * The width of the DSLR-A100's image.
     */
    private static final int A100_WIDTH = 3880;

    /**
     * The height of the DSLR-A100's image.
     */
    private static final int A100_HEIGHT = 2600;

    /**
     * The longest {@code "\0MRI"} block of the DSLR-A100 that is read.
     */
    private static final int A100_MAX_BLOCK = 1 << 16;

    /**
     * The name of the block of the DSLR-A100 that holds its other blocks, {@code "\0MRI"}.
     */
    private static final int A100_MRI = 0x004D5249;

    /**
     * The name of the white balance block of the DSLR-A100, {@code "\0WBG"}.
     */
    private static final int A100_WBG = 0x00574247;

    /**
     * The models without the black level tag whose black level is 800.
     */
    private static final Set<String> BLACK_800 = Set.of("DSC-RX0", "DSC-RX10", "DSC-RX10M3", "DSC-RX100",
            "DSC-RX100M2", "DSC-RX100M3", "DSC-RX100M4", "DSC-RX100M5");

    /**
     * The models whose photosites saturate below the white level their tag states, or whose white
     * level differs from that of the models without the tag; a key with a bit depth applies to the
     * cRAW files of that depth only.
     */
    private static final Map<String, Integer> WHITE_OF_MODEL = Map.ofEntries(
            Map.entry("DSC-HX95", 16300), Map.entry("DSC-HX99", 16300), Map.entry("DSC-RX0M2", 16300),
            Map.entry("DSC-RX100M5A", 16300), Map.entry("DSC-RX100M6", 16300), Map.entry("DSC-RX100M7", 16300),
            Map.entry("DSC-RX100M7A", 16300), Map.entry("ZV-1", 16300), Map.entry("ZV-1M2", 16300),
            Map.entry("ILCE-7M3", 16300), Map.entry("ILCE-7RM4A/12", 15000), Map.entry("DSC-R1", 16383),
            Map.entry("DSLR-A700", 16383));

    /**
     * The black levels of the models without the black level tag whose photosites' levels differ, in
     * the raster order of their 2x2 color filter array.
     */
    private static final Map<String, double[]> BLACK_OF_MODEL = Map.of("DSC-R1", new double[]{514, 511, 512, 513});

    /**
     * The image areas, as x, y, width and height, of the models whose EXIF image size is the whole
     * sensor image.
     */
    private static final Map<String, double[]> CROP_OF_MODEL = Map.of("DSLR-A900", new double[]{12, 8, 6048, 4032},
            "DSLR-A700", new double[]{4, 4, 4272, 2848});

    /**
     * Prevents instantiation.
     */
    private ArwParser() {
        throw new AssertionError();
    }

    /**
     * Parses the file.
     *
     * @param reader the TIFF structure
     * @param file the description to fill
     */
    static void parse(TiffReader reader, RawFile file) {
        var tree = new TiffTree(reader);
        tree.describe(file);
        var model = file.model == null ? "" : file.model.strip();
        if (model.equals(A100)) {
            a100(reader, tree, file);
            return;
        }
        var raw = TiffEpParser.rawDirectory(tree);
        if (raw == null) {
            if (ycbcr(tree)) {
                // TODO: convert the YCbCr samples once a second reference pins down the chroma upsampling and the
                //  levels Adobe DNG Converter applies (the raw module's bug log, item 11, holds the analysis)
                file.unsupported = "Sony lossless compressed RAW at the S and M sizes (YCbCr samples) is not supported";
            } else if (!arq(reader, tree, file)) {
                file.unsupported = "the ARW file holds no raw image";
            }
            return;
        }
        var sensor = new Sensor();
        var width = raw.intValue(TiffSamples.IMAGE_WIDTH, 0);
        var height = raw.intValue(TiffSamples.IMAGE_LENGTH, 0);
        var bits = raw.intValue(TiffSamples.BITS_PER_SAMPLE, 12);
        var compression = raw.intValue(TiffSamples.COMPRESSION, 1);
        if (width <= 0 || height <= 0) {
            throw new MediaException.Malformed("invalid ARW raw image of " + width + "x" + height);
        }
        var counts = raw.longs(TiffSamples.STRIP_BYTE_COUNTS);
        var total = 0L;
        if (counts != null) {
            for (var count : counts) {
                total += count;
            }
        }
        var pixels = (long) width * height;
        var curve = raw.longs(0x7010);
        var packed = false;
        var deltaCoded = compression == SONY_COMPRESSION && DELTA_CODED.contains(model)
                && raw.intValue(SONY_RAW_TYPE, SONY_RAW_TYPE_DELTA) == SONY_RAW_TYPE_DELTA;
        if (deltaCoded) {
            var offsets = raw.longs(TiffSamples.STRIP_OFFSETS);
            if (offsets == null || offsets.length == 0 || total <= 0) {
                throw new MediaException.Malformed("ARW raw image without data");
            }
            sensor.width = width;
            sensor.height = height;
            sensor.bitsPerSample = 12;
            sensor.compression = "Sony variable-length";
            sensor.reader = SonyDeltaCoding.reader(reader, reader.base() + offsets[0], total, width);
        } else if (compression == SONY_COMPRESSION && total == pixels) {
            var offsets = raw.longs(TiffSamples.STRIP_OFFSETS);
            if (offsets == null || offsets.length == 0) {
                throw new MediaException.Malformed("ARW raw image without data");
            }
            var offset = reader.base() + offsets[0];
            var table = toneCurve(curve);
            sensor.width = width;
            sensor.height = height;
            sensor.bitsPerSample = 14;
            sensor.compression = "Sony cRAW";
            sensor.reader = (plane, limits) -> craw(reader, offset, plane, table, limits);
        } else if (compression == SONY_COMPRESSION && total == pixels * 3 / 2 && width % 2 == 0) {
            var offsets = raw.longs(TiffSamples.STRIP_OFFSETS);
            if (offsets == null || offsets.length == 0) {
                throw new MediaException.Malformed("ARW raw image without data");
            }
            var offset = reader.base() + offsets[0];
            var source = reader.source();
            var row = PackedRows.of(PackedRows.Layout.LSB_FIRST, 12);
            sensor.width = width;
            sensor.height = height;
            sensor.bitsPerSample = 12;
            sensor.compression = "uncompressed";
            sensor.reader = (plane, limits) -> PackedRows.read(source, offset, width * 3 / 2, row, plane, limits);
            packed = true;
        } else if (compression == SONY_COMPRESSION) {
            file.unsupported = "Sony compression 32767 of model " + model + " at " + total + " bytes for " + pixels
                    + " photosites is not supported";
            balance(reader, tree.first, raw, file);
            return;
        } else if (total >= pixels * 2) {
            try {
                TiffSamples.describe(raw, sensor);
            } catch (MediaException.Unsupported exception) {
                sensor.width = width;
                sensor.height = height;
            }
            sensor.bitsPerSample = 14;
            var blocks = TiffSamples.blocks(raw, width, height);
            var source = reader.source();
            var little = reader.little();
            sensor.compression = "uncompressed";
            sensor.reader = (plane, limits) -> {
                for (var block : blocks) {
                    LimitChecks.checkAllocation(limits, block.length());
                    var data = new byte[(int) Math.min(block.length(), Integer.MAX_VALUE - 16)];
                    source.readFully(block.offset(), data, 0, data.length);
                    var rows = Math.min(block.height(), plane.height - block.y());
                    for (var row = 0; row < rows; row++) {
                        var start = row * plane.width * 2;
                        if (start >= data.length) {
                            break;
                        }
                        Unpack.words(data, start, data.length, little, 16, plane.integers,
                                (block.y() + row) * plane.width, plane.width);
                    }
                }
            };
        } else {
            try {
                sensor.reader = TiffSamples.describe(raw, sensor);
                if (compression == LOSSLESS_JPEG) {
                    sensor.reader = quads(reader, raw, sensor, sensor.reader);
                }
            } catch (MediaException.Unsupported exception) {
                file.unsupported = exception.getMessage();
                return;
            }
        }
        sensor.cfa = TiffEpParser.cfa(raw, tree.exif);
        if (sensor.cfa == null) {
            file.unsupported = "the ARW raw image has no CFA pattern";
            return;
        }
        var crawBits = compression == SONY_COMPRESSION && !packed && !deltaCoded ? bits : 0;
        levels(reader, tree.first, raw, file, sensor, crawBits, packed || deltaCoded);
        if (deltaCoded) {
            deltaBlack(reader, tree.first, raw, sensor);
        }
        var crop = CROP_OF_MODEL.get(model);
        var stated = statedCrop(raw, sensor);
        if (deltaCoded) {
            sensor.defaultCrop = deltaCrop(sensor);
        } else if (stated != null) {
            sensor.defaultCrop = stated;
        } else {
            sensor.defaultCrop = crop != null && crop[0] + crop[2] <= sensor.width && crop[1] + crop[3] <= sensor.height
                    ? crop.clone() : centeredCrop(tree.exif, sensor);
        }
        sensor.lens = VendorLens.sony(raw, file.model, sensor.width, sensor.height);
        file.sensor = sensor;
    }

    /**
     * Returns the reader of the lossless JPEG blocks of a color filter array image whose frames hold four components
     * at half the block's width and height, as Sony's lossless compression stores them: the four samples of a frame
     * pixel are the two-by-two photosites it covers, row by row, not four photosites of one row as the raster order
     * of DNG files places them. Blocks of another frame shape keep the raster order.
     *
     * @param reader the TIFF structure
     * @param raw the raw image directory
     * @param sensor the sensor description, its size and samples set
     * @param stored the reader placing the samples in raster order
     * @return the reader
     */
    private static Sensor.Reader quads(TiffReader reader, Ifd raw, Sensor sensor, Sensor.Reader stored) {
        if (sensor.samplesPerPixel != 1 || sensor.floating) {
            return stored;
        }
        var blocks = TiffSamples.blocks(raw, sensor.width, sensor.height);
        var first = blocks[0];
        var frame = losslessFrame(reader.bytes(first.offset(), Math.min(first.length(), FRAME_HEADER_BYTES)));
        var blockWidth = first.width();
        var blockHeight = first.height();
        if (frame == null || frame[2] != QUAD_COMPONENTS || frame[0] * 2 != blockWidth || frame[1] * 2 != blockHeight) {
            return stored;
        }
        var paddedWidth = (sensor.width + blockWidth - 1) / blockWidth * blockWidth;
        var paddedHeight = (sensor.height + blockHeight - 1) / blockHeight * blockHeight;
        return (plane, limits) -> {
            // the raster order fills whole blocks, so the plane it fills is padded to whole blocks
            var padded = new SensorPlane(paddedWidth, paddedHeight, 1, false, limits);
            stored.read(padded, limits);
            var in = padded.integers;
            var out = plane.integers;
            for (var top = 0; top < plane.height; top += blockHeight) {
                for (var left = 0; left < plane.width; left += blockWidth) {
                    for (var y = 0; y < blockHeight / 2; y++) {
                        for (var index = 0; index < 2 * blockWidth; index++) {
                            var component = index & 3;
                            var row = top + 2 * y + (component >> 1);
                            var column = left + 2 * (index >> 2) + (component & 1);
                            if (row < plane.height && column < plane.width) {
                                out[row * plane.width + column] = in[(top + 2 * y + index / blockWidth) * paddedWidth
                                        + left + index % blockWidth];
                            }
                        }
                    }
                }
            }
        };
    }

    /**
     * Reads the frame header (SOF3) of a lossless JPEG stream.
     *
     * @param data the first bytes of the stream
     * @return the frame's width, height and number of components, or {@code null} when the bytes hold no lossless
     * frame header
     */
    private static int[] losslessFrame(byte[] data) {
        if (data.length < 4 || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) {
            return null;
        }
        var position = 2;
        while (position + 4 <= data.length && (data[position] & 0xFF) == 0xFF) {
            var marker = data[position + 1] & 0xFF;
            var length = ByteArrays.u16be(data, position + 2);
            if (marker == 0xC3) {
                if (position + 10 > data.length) {
                    return null;
                }
                return new int[]{ByteArrays.u16be(data, position + 7), ByteArrays.u16be(data, position + 5),
                        data[position + 9] & 0xFF};
            }
            if (marker == 0xDA || length < 2) {
                return null;
            }
            position += 2 + length;
        }
        return null;
    }

    /**
     * Sets the black level of variable-length coded data, for a directory without the black level tag: that of the
     * encrypted {@code SR2} directory, or zero when it records none.
     *
     * @param reader the TIFF structure
     * @param first the first directory
     * @param raw the raw image directory
     * @param sensor the sensor description, its color filter array set
     */
    private static void deltaBlack(TiffReader reader, Ifd first, Ifd raw, Sensor sensor) {
        if (raw.has(0x7310)) {
            return;
        }
        var black = Sr2Balance.black(reader, first);
        if (black == null) {
            sensor.black = new double[]{0};
            return;
        }
        sensor.blackRows = 2;
        sensor.blackColumns = 2;
        sensor.black = SrwParser.cfaOrder(sensor.cfa, black);
    }

    /**
     * Returns the image area of variable-length coded data, as the converter crops it.
     *
     * @param sensor the sensor description
     * @return the area four photosites inside each edge, as x, y, width and height, or {@code null} for a sensor image
     * too small to have one
     */
    private static double[] deltaCrop(Sensor sensor) {
        return sensor.width > 2 * DELTA_BORDER && sensor.height > 2 * DELTA_BORDER
                ? new double[]{DELTA_BORDER, DELTA_BORDER, sensor.width - 2 * DELTA_BORDER,
                sensor.height - 2 * DELTA_BORDER} : null;
    }

    /**
     * Returns whether a file holds Sony's lossless compressed RAW at a reduced size: a raw directory, marked by tag
     * {@code 0x7000}, of YCbCr samples (photometric interpretation 6) in lossless JPEG tiles.
     *
     * @param tree the directories
     * @return whether the file holds such a directory
     */
    private static boolean ycbcr(TiffTree tree) {
        for (var ifd : tree.directories) {
            if (ifd.has(SONY_RAW_TYPE) && ifd.intValue(TiffSamples.PHOTOMETRIC, 0) == PHOTOMETRIC_YCBCR
                    && ifd.intValue(TiffSamples.COMPRESSION, 1) == LOSSLESS_JPEG) {
                return true;
            }
        }
        return false;
    }

    /**
     * Describes the image of a DSLR-A100 file.
     *
     * @param reader the TIFF structure
     * @param tree the directories
     * @param file the description to fill
     */
    private static void a100(TiffReader reader, TiffTree tree, RawFile file) {
        var offsets = tree.first.longs(TiffTree.SUB_IFDS);
        if (offsets == null || offsets.length != 1 || offsets[0] <= 0) {
            file.unsupported = "the ARW file holds no raw image";
            return;
        }
        var offset = reader.base() + offsets[0];
        var sensor = new Sensor();
        sensor.width = A100_WIDTH;
        sensor.height = A100_HEIGHT;
        sensor.bitsPerSample = 12;
        sensor.compression = "Sony variable-length";
        sensor.cfa = CfaPattern.bayer("GRBG");
        sensor.black = new double[]{0};
        sensor.white = new double[]{PACKED_WHITE};
        sensor.reader = SonyDeltaCoding.reader(reader, offset, Long.MAX_VALUE, A100_STREAM_WIDTH);
        sensor.defaultCrop = deltaCrop(sensor);
        a100Balance(reader, tree.first, file);
        sensor.estimateBalance = file.color.asShotNeutral == null;
        file.sensor = sensor;
    }

    /**
     * Sets the as-shot white balance of a DSLR-A100 file from its {@code "\0WBG"} block, if it has one.
     *
     * @param reader the TIFF structure
     * @param first the first directory
     * @param file the description to fill
     */
    private static void a100Balance(TiffReader reader, Ifd first, RawFile file) {
        var pointer = first.bytes(0xC634);
        if (pointer == null || pointer.length != 4) {
            return;
        }
        try {
            var start = reader.base() + (reader.little() ? ByteArrays.u32le(pointer, 0) : ByteArrays.u32be(pointer, 0));
            var header = reader.bytes(start, 4);
            if (ByteArrays.u32be(header, 0) != A100_MRI) {
                return;
            }
            var at = start + 8;
            var last = at + Math.min(reader.u32(start + 4), A100_MAX_BLOCK);
            while (at + 8 <= last) {
                var name = ByteArrays.u32be(reader.bytes(at, 4), 0);
                var length = reader.u32(at + 4);
                if (at + 8 + length > last) {
                    return;
                }
                if (name == A100_WBG && length >= 12) {
                    var levels = reader.bytes(at + 8, 12);
                    var red = ByteArrays.u16(levels, 4, reader.little());
                    var green = (ByteArrays.u16(levels, 6, reader.little()) + ByteArrays.u16(levels, 8, reader.little()))
                            / 2.0;
                    var blue = ByteArrays.u16(levels, 10, reader.little());
                    if (red > 0 && green > 0 && blue > 0) {
                        file.color.asShotNeutral = new double[]{green / red, 1, green / blue};
                    }
                    return;
                }
                at += 8 + length;
            }
        } catch (MediaException.Malformed exception) {
            // a damaged block leaves the white balance to the estimate
        }
    }

    /**
     * Describes the image of a Sony ARQ file, the merged pixel-shift capture of four frames that Sony's software
     * writes: a directory of photometric interpretation 32892 holding, as uncompressed 16-bit words, four samples
     * per pixel whose colors {@code CFAPlaneColor} gives as red, green, green and blue, or the same samples in
     * lossless JPEG strips or tiles. The image is merged to
     * red, the mean of the two greens rounded half up, and blue; the levels are those of the directory's
     * {@code BlackLevel} (tag {@code 0x7310}), {@code WhiteLevel} and {@code WB_RGGBLevels} (tag {@code 0x7313})
     * tags, and the crop that of its {@code DefaultCropOrigin} and {@code DefaultCropSize}.
     *
     * @param reader the TIFF structure
     * @param tree the directories
     * @param file the description to fill
     * @return whether the file is an ARQ file
     */
    private static boolean arq(TiffReader reader, TiffTree tree, RawFile file) {
        Ifd raw = null;
        for (var ifd : tree.directories) {
            if (ifd.intValue(TiffSamples.PHOTOMETRIC, 0) == PHOTOMETRIC_SEQUENTIAL
                    && ifd.intValue(TiffSamples.SAMPLES_PER_PIXEL, 1) == ARQ_SAMPLES) {
                raw = ifd;
                break;
            }
        }
        if (raw == null) {
            return false;
        }
        var colors = raw.longs(50710);
        if (colors == null || colors.length != ARQ_SAMPLES || colors[0] != 0 || colors[1] != 1 || colors[2] != 1
                || colors[3] != 2) {
            file.unsupported = "ARQ files of other sample colors than red, green, green and blue are not supported";
            return true;
        }
        var compression = raw.intValue(TiffSamples.COMPRESSION, 1);
        if (compression != 1 && compression != LOSSLESS_JPEG) {
            // TODO: read the other compressions once a file or Adobe DNG Converter shows an ARQ file using one; the
            //  converter refuses copies of the CC0 ILCE-7RM3 file marked as compression 32767
            file.unsupported = "ARQ files of TIFF compression " + compression + " are not supported";
            return true;
        }
        var width = raw.intValue(TiffSamples.IMAGE_WIDTH, 0);
        var height = raw.intValue(TiffSamples.IMAGE_LENGTH, 0);
        if (width <= 0 || height <= 0) {
            throw new MediaException.Malformed("invalid ARQ raw image of " + width + "x" + height);
        }
        var sensor = new Sensor();
        if (compression == LOSSLESS_JPEG) {
            var stored = new Sensor();
            Sensor.Reader samples;
            try {
                samples = TiffSamples.describe(raw, stored);
            } catch (MediaException.Unsupported exception) {
                file.unsupported = exception.getMessage();
                return true;
            }
            sensor.reader = (plane, limits) -> {
                var four = new SensorPlane(width, height, ARQ_SAMPLES, false, limits);
                samples.read(four, limits);
                var in = four.integers;
                var out = plane.integers;
                for (var index = 0; index < width * height; index++) {
                    var red = in[index * ARQ_SAMPLES] & 0xFFFF;
                    var green = (in[index * ARQ_SAMPLES + 1] & 0xFFFF) + (in[index * ARQ_SAMPLES + 2] & 0xFFFF);
                    var blue = in[index * ARQ_SAMPLES + 3] & 0xFFFF;
                    out[index * 3] = (short) red;
                    out[index * 3 + 1] = (short) ((green + 1) >>> 1);
                    out[index * 3 + 2] = (short) blue;
                }
            };
        }
        sensor.width = width;
        sensor.height = height;
        sensor.samplesPerPixel = 3;
        sensor.bitsPerSample = 14;
        sensor.compression = compression == LOSSLESS_JPEG ? "lossless JPEG" : "uncompressed";
        if (compression == 1) {
            sensor.reader = uncompressedArq(reader, raw, width, height);
        }
        var black = raw.longs(0x7310);
        if (black != null && black.length >= 4) {
            sensor.black = new double[]{black[0], (black[1] + black[2]) / 2.0, black[3]};
        } else {
            sensor.black = new double[]{BLACK, BLACK, BLACK};
        }
        var white = raw.longValue(50717, 0);
        var level = white > 0 && white < 1 << 16 ? white : WHITE;
        sensor.white = new double[]{level, level, level};
        var balance = raw.longs(0x7313);
        if (balance != null && balance.length >= 4 && balance[0] > 0 && balance[1] > 0 && balance[2] > 0
                && balance[3] > 0) {
            var green = (balance[1] + balance[2]) / 2.0;
            file.color.asShotNeutral = new double[]{green / balance[0], 1, green / balance[3]};
        }
        var origin = raw.doubles(50719);
        var size = raw.doubles(50720);
        if (origin != null && size != null && origin.length == 2 && size.length == 2 && size[0] > 0 && size[1] > 0
                && origin[0] >= 0 && origin[1] >= 0 && origin[0] + size[0] <= width
                && origin[1] + size[1] <= height) {
            sensor.defaultCrop = new double[]{origin[0], origin[1], size[0], size[1]};
        }
        sensor.lens = VendorLens.sony(raw, file.model, width, height);
        file.sensor = sensor;
        return true;
    }

    /**
     * Returns the reader of an ARQ file's uncompressed samples.
     *
     * @param reader the TIFF structure
     * @param raw the raw image directory
     * @param width the width in pixels
     * @param height the height in pixels
     * @return the reader
     */
    private static Sensor.Reader uncompressedArq(TiffReader reader, Ifd raw, int width, int height) {
        var blocks = TiffSamples.blocks(raw, width, height);
        var source = reader.source();
        var little = reader.little();
        return (plane, limits) -> {
            var stride = (long) width * ARQ_SAMPLES * 2;
            LimitChecks.checkAllocation(limits, stride);
            var row = new byte[(int) stride];
            var out = plane.integers;
            for (var block : blocks) {
                var rows = Math.min(block.height(), plane.height - block.y());
                for (var line = 0; line < rows; line++) {
                    var start = (long) line * stride;
                    if (start >= block.length()) {
                        break;
                    }
                    var count = (int) Math.min(stride, block.length() - start);
                    var read = SourceBytes.readAvailable(source, block.offset() + start, row, 0, count);
                    var pixels = Math.max(read, 0) / (ARQ_SAMPLES * 2);
                    var target = (block.y() + line) * width * 3;
                    for (var x = 0; x < pixels; x++) {
                        var at = x * ARQ_SAMPLES * 2;
                        var red = ByteArrays.u16(row, at, little);
                        var green = ByteArrays.u16(row, at + 2, little) + ByteArrays.u16(row, at + 4, little);
                        var blue = ByteArrays.u16(row, at + 6, little);
                        out[target + x * 3] = (short) red;
                        out[target + x * 3 + 1] = (short) ((green + 1) >>> 1);
                        out[target + x * 3 + 2] = (short) blue;
                    }
                }
            }
        };
    }

    /**
     * Sets the black and white levels and the white balance of a file.
     *
     * @param reader the TIFF structure
     * @param first the first directory
     * @param raw the raw image directory
     * @param file the description to fill
     * @param sensor the sensor description, its color filter array set
     * @param crawBits the bit depth of cRAW data, or {@code 0} for other data
     * @param packed whether the data is packed 12-bit samples on the 12-bit scale, whose untagged levels are a
     * quarter of the 14-bit ones and whose white level is the largest 12-bit value
     */
    private static void levels(TiffReader reader, Ifd first, Ifd raw, RawFile file, Sensor sensor, int crawBits,
                               boolean packed) {
        var model = file.model == null ? "" : file.model.strip();
        var tagged = raw.longs(0x7310);
        double[] black = null;
        if (tagged != null && tagged.length >= 4) {
            black = SrwParser.cfaOrder(sensor.cfa, new double[]{tagged[0], tagged[1], tagged[2], tagged[3]});
        } else if (BLACK_OF_MODEL.containsKey(model)) {
            black = BLACK_OF_MODEL.get(model).clone();
        }
        if (black != null) {
            sensor.blackRows = 2;
            sensor.blackColumns = 2;
            sensor.black = black;
        } else {
            var untagged = BLACK_800.contains(model) ? 800 : BLACK;
            sensor.black = new double[]{packed ? untagged >> 2 : untagged};
        }
        var white = WHITE_OF_MODEL.get(model + "/" + crawBits);
        if (white == null) {
            white = WHITE_OF_MODEL.get(model);
        }
        if (white == null) {
            var tag = raw.longValue(50717, 0);
            white = tag > 0 && tag < 1 << 16 ? (int) tag : packed ? PACKED_WHITE : WHITE;
        }
        sensor.white = new double[]{white};
        sensor.estimateBalance = !balance(reader, first, raw, file);
    }

    /**
     * Sets the as-shot white balance of a file from the raw directory's {@code WB_RGGBLevels} tag, or else from the
     * encrypted {@code SR2} directory where its layout is known.
     *
     * @param reader the TIFF structure
     * @param first the first directory
     * @param raw the raw image directory
     * @param file the description to fill
     * @return whether the file records its white balance where it is read
     */
    private static boolean balance(TiffReader reader, Ifd first, Ifd raw, RawFile file) {
        var balance = raw.longs(0x7313);
        if (balance != null && balance.length >= 4 && balance[0] > 0 && balance[1] > 0 && balance[2] > 0
                && balance[3] > 0) {
            var green = (balance[1] + balance[2]) / 2.0;
            file.color.asShotNeutral = new double[]{green / balance[0], 1, green / balance[3]};
            return true;
        }
        var neutral = Sr2Balance.neutral(reader, first);
        if (neutral == null) {
            return false;
        }
        file.color.asShotNeutral = neutral;
        return true;
    }

    /**
     * Returns the default crop the raw image directory of the newer models states, with the DNG tags
     * {@code DefaultCropOrigin} and {@code DefaultCropSize}: the ILCE-1M2's image starts 12 photosites from the
     * top-left corner of a sensor image padded far past it, where a centered crop would take in the padding.
     *
     * @param raw the raw image directory
     * @param sensor the sensor description, its size set
     * @return the crop as x, y, width and height, or {@code null} when the directory states none inside the image
     */
    private static double[] statedCrop(Ifd raw, Sensor sensor) {
        var origin = raw.doubles(50719);
        var size = raw.doubles(50720);
        if (origin == null || size == null || origin.length != 2 || size.length != 2 || size[0] <= 0 || size[1] <= 0
                || origin[0] < 0 || origin[1] < 0 || origin[0] + size[0] > sensor.width
                || origin[1] + size[1] > sensor.height) {
            return null;
        }
        return new double[]{origin[0], origin[1], size[0], size[1]};
    }

    /**
     * Returns the default crop of a Sony file: the image size the EXIF directory records, centered
     * on the sensor image on even coordinates so the color filter array keeps its phase.
     *
     * @param exif the EXIF directory, or {@code null}
     * @param sensor the sensor description
     * @return the crop as x, y, width and height, or {@code null} when the EXIF size is absent or
     * not slightly smaller than the sensor image
     */
    static double[] centeredCrop(Ifd exif, Sensor sensor) {
        if (exif == null) {
            return null;
        }
        var width = exif.intValue(40962, 0);
        var height = exif.intValue(40963, 0);
        if (width <= 0 || height <= 0 || width > sensor.width || height > sensor.height
                || width < sensor.width * 0.9 || height < sensor.height * 0.9) {
            return null;
        }
        var left = ((sensor.width - width) / 2) & ~1;
        var top = ((sensor.height - height) / 2) & ~1;
        return new double[]{left, top, width, height};
    }

    /**
     * Builds the cRAW tone curve, mapping twice an 11-bit value to a 14-bit linear value.
     *
     * @param thresholds the four thresholds of tag {@code 0x7010}, or {@code null} for Sony's
     * usual ones
     * @return the curve, of 4096 entries
     */
    static int[] toneCurve(long[] thresholds) {
        var knots = new int[]{0, 2000, 2600, 3225, 3525, 4095};
        if (thresholds != null && thresholds.length >= 4) {
            var valid = true;
            for (var index = 0; index < 4; index++) {
                var knot = (int) (thresholds[index] >> 2) & 0xFFF;
                valid &= knot > knots[index];
                knots[index + 1] = knot;
            }
            if (!valid || knots[4] >= 4095) {
                knots = new int[]{0, 2000, 2600, 3225, 3525, 4095};
            }
        }
        var curve = new int[4096];
        var value = 0;
        for (var segment = 0; segment < 5; segment++) {
            for (var index = knots[segment] + 1; index <= knots[segment + 1]; index++) {
                value += 1 << segment;
                curve[index] = value;
            }
        }
        for (var index = 0; index < curve.length; index++) {
            curve[index] = Math.min(curve[index], 16383);
        }
        return curve;
    }

    /**
     * Decodes cRAW sensor data.
     *
     * @param reader the TIFF structure
     * @param offset the absolute offset of the data
     * @param plane the plane to fill
     * @param curve the tone curve
     * @param limits the resource bounds
     */
    private static void craw(TiffReader reader, long offset, SensorPlane plane, int[] curve, MediaLimits limits) {
        var width = plane.width;
        LimitChecks.checkAllocation(limits, width);
        var groups = width / 32;
        var bands = ParallelBands.bands(plane.height, (long) width * plane.height);
        ParallelBands.run(bands, band -> {
            var first = ParallelBands.start(band, bands, plane.height);
            var last = ParallelBands.start(band + 1, bands, plane.height);
            var row = new byte[width];
            var values = new int[16];
            var out = plane.integers;
            for (var y = first; y < last; y++) {
                var read = reader.readAvailable(offset + (long) y * width, row, 0, width);
                if (read < width) {
                    Arrays.fill(row, Math.max(read, 0), width, (byte) 0);
                }
                var line = y * width;
                for (var group = 0; group < groups; group++) {
                    var x = group * 32;
                    for (var half = 0; half < 2; half++) {
                        block(row, x + 16 * half, values);
                        for (var index = 0; index < 16; index++) {
                            out[line + x + 2 * index + half] = (short) curve[values[index] << 1];
                        }
                    }
                }
            }
        });
    }

    /**
     * Decodes one 16-byte cRAW block.
     *
     * @param data the bytes
     * @param start the index of the block
     * @param values receives the 16 values, 0 to 2047
     */
    static void block(byte[] data, int start, int[] values) {
        var low = 0L;
        var high = 0L;
        for (var index = 7; index >= 0; index--) {
            low = (low << 8) | (data[start + index] & 0xFF);
            high = (high << 8) | (data[start + 8 + index] & 0xFF);
        }
        var max = (int) (low & 0x7FF);
        var min = (int) ((low >>> 11) & 0x7FF);
        var maxIndex = (int) ((low >>> 22) & 0xF);
        var minIndex = (int) ((low >>> 26) & 0xF);
        var shift = 0;
        while (shift < 4 && (0x80 << shift) <= max - min) {
            shift++;
        }
        var bit = 30;
        for (var index = 0; index < 16; index++) {
            if (index == maxIndex) {
                values[index] = max;
            } else if (index == minIndex) {
                values[index] = min;
            } else {
                long field;
                if (bit + 7 <= 64) {
                    field = low >>> bit;
                } else if (bit >= 64) {
                    field = high >>> (bit - 64);
                } else {
                    field = (low >>> bit) | (high << (64 - bit));
                }
                var delta = (int) (field & 0x7F);
                values[index] = Math.min(min + (delta << shift), 0x7FF);
                bit += 7;
            }
        }
    }
}
