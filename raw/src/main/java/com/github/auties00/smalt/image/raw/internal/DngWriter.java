/* Ports the Digital Negative (DNG) Specification 1.7.1.0, Adobe. This product includes DNG technology under license
   by Adobe. The NewRawImageDigest computation ports the Adobe DNG SDK 1.7.1 build 2768 (dng_negative.cpp,
   dng_find_new_raw_image_digest_task). Copyright 2006-2019 Adobe Systems Incorporated; DNG SDK License Agreement.
   Modified for Smalt. */
package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifValue;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.ResizeFilter;
import com.github.auties00.smalt.image.raw.DngCompression;
import com.github.auties00.smalt.image.tiff.internal.IfdBuilder;
import com.github.auties00.smalt.image.tiff.internal.TiffExifWriter;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.internal.tiff.TiffTypes;
import com.github.auties00.smalt.io.MediaSink;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Writes a DNG file (Adobe DNG specification 1.7.1) from a {@link DngContent}: a little-endian classic TIFF
 * whose first directory holds the file's metadata and, when the content has a rendering, an uncompressed RGB
 * thumbnail, with the raw image and a baseline JPEG preview in its sub-directories; without a rendering the raw
 * image is the first directory.
 *
 * <p>The raw image is stored in tiles of 16-bit samples, uncompressed or as lossless JPEG streams, with a black
 * level of 0 and a white level of 65535: the samples are linear reference values, so the file has no
 * linearization table, black levels, active area or opcode lists 1 and 2. The color description is the content's
 * calibration, or one derived from its neutral and matrix: the matrix as forward matrix (scaled to map a
 * white-balanced neutral to the D50 white when it maps it elsewhere), the color matrix that maps the D50 white to
 * the neutral, and the D50 calibration illuminant.
 */
final class DngWriter {
    /**
     * The largest classic TIFF file.
     */
    private static final long MAX_FILE = 0xFFFF_FFFFL;

    /**
     * The longest side of the thumbnail in the first directory.
     */
    private static final int THUMBNAIL_SIZE = 256;

    /**
     * The approximate number of operations to code one sample.
     */
    private static final long SAMPLE_WORK = 24;

    /**
     * The tile width and height of the {@code NewRawImageDigest}.
     */
    private static final int DIGEST_TILE = 256;

    /**
     * The largest difference from the D50 white of each component of the white a forward matrix written as it is
     * maps a white-balanced neutral to.
     */
    private static final double WHITE_TOLERANCE = 0.01;

    /**
     * The EXIF light source code of D50.
     */
    private static final int D50 = 23;

    /**
     * The EXIF light source code of an illuminant given by its chromaticity.
     */
    private static final int OTHER_ILLUMINANT = 255;

    /**
     * The value of the {@code PreviewColorSpace} tag for sRGB.
     */
    private static final int PREVIEW_SRGB = 2;

    /**
     * The first tag of the range the DNG specification defines, never copied from EXIF metadata.
     */
    private static final int FIRST_DNG_TAG = 50706;

    /**
     * The last tag of the range the DNG specification defines.
     */
    private static final int LAST_DNG_TAG = 52700;

    /**
     * The EXIF {@code LensSpecification} tag.
     */
    private static final int LENS_SPECIFICATION = 0xA432;

    /**
     * The EXIF {@code UserComment} tag.
     */
    private static final int USER_COMMENT = 0x9286;

    /**
     * The character codes EXIF defines for a {@code UserComment} other than the undefined one: ASCII, Unicode and
     * JIS.
     */
    private static final Set<String> DEFINED_CODES = Set.of("ASCII\0\0\0", "UNICODE\0", "JIS\0\0\0\0\0");

    /**
     * The ASCII character code of a {@code UserComment}.
     */
    private static final byte[] ASCII_CODE = "ASCII\0\0\0".getBytes(StandardCharsets.ISO_8859_1);

    /**
     * The name the previews give their application.
     */
    private static final String APPLICATION = "Smalt";

    /**
     * The tags of the color matrices of the three calibration illuminants.
     */
    private static final int[] COLOR_MATRIX = {50721, 50722, 52531};

    /**
     * The tags of the camera calibration matrices.
     */
    private static final int[] CAMERA_CALIBRATION = {50723, 50724, 52530};

    /**
     * The tags of the forward matrices.
     */
    private static final int[] FORWARD_MATRIX = {50964, 50965, 52532};

    /**
     * The tags of the calibration illuminants.
     */
    private static final int[] ILLUMINANT = {50778, 50779, 52529};

    /**
     * The tags of the custom illuminant data.
     */
    private static final int[] ILLUMINANT_DATA = {52533, 52534, 52535};

    /**
     * The tags of the hue, saturation and value maps.
     */
    private static final int[] HUE_SAT_MAP = {50938, 50939, 52537};

    /**
     * Prevents instantiation.
     */
    private DngWriter() {
        throw new AssertionError();
    }

    /**
     * Writes a DNG file.
     *
     * @param content the content to write
     * @param sink the output, written from its position; not closed
     * @throws MediaException.LimitExceeded if the file would exceed 4 GiB
     * @throws MediaException.Io if writing the sink fails
     */
    static void write(DngContent content, MediaSink sink) {
        var tiles = tiles(content);
        byte[] jpeg = null;
        byte[] thumbnail = null;
        int[] previewSize = null;
        int[] thumbnailSize = null;
        if (content.preview != null) {
            var rendering = ImageOps.toRgba(content.preview);
            previewSize = new int[]{rendering.width(), rendering.height()};
            jpeg = JpegEncoder.encode(rendering, JpegEncoderOptions.builder().quality(content.previewQuality)
                    .chromaFormat(ChromaFormat.YUV420).build());
            thumbnailSize = fit(rendering.width(), rendering.height(), THUMBNAIL_SIZE);
            var small = thumbnailSize[0] == rendering.width() && thumbnailSize[1] == rendering.height() ? rendering
                    : ImageOps.toRgba(ImageOps.resize(rendering, thumbnailSize[0], thumbnailSize[1],
                    ResizeFilter.LANCZOS3));
            thumbnail = rgb(small);
        }
        var backward = backwardVersion(content);
        var raw = rawDirectory(content, tiles);
        IfdBuilder first;
        IfdBuilder preview = null;
        if (jpeg != null) {
            first = new IfdBuilder(true, false);
            first.longs(254, 1);
            pixels(first, thumbnailSize[0], thumbnailSize[1], 1, 2, thumbnail.length);
            first.longs(50970, PREVIEW_SRGB);
            preview = new IfdBuilder(true, false);
            preview.longs(254, 1);
            pixels(preview, previewSize[0], previewSize[1], 7, 6, jpeg.length);
            preview.shorts(530, 2, 2);
            preview.longs(50970, PREVIEW_SRGB);
            preview.ascii(50966, APPLICATION);
            first.longs(330, 0, 0);
        } else {
            first = raw;
        }
        metadata(first, content, backward);
        var position = 8 + first.size();
        var rawOffset = 8L;
        var previewOffset = 0L;
        if (preview != null) {
            rawOffset = position;
            position += raw.size();
            previewOffset = position;
            position += preview.size();
        }
        long thumbnailOffset = 0;
        if (thumbnail != null) {
            thumbnailOffset = position;
            position += even(thumbnail.length);
        }
        var tileOffsets = new long[tiles.length];
        var tileCounts = new long[tiles.length];
        for (var index = 0; index < tiles.length; index++) {
            tileOffsets[index] = position;
            tileCounts[index] = tiles[index].length;
            position += even(tiles[index].length);
        }
        long jpegOffset = 0;
        if (jpeg != null) {
            jpegOffset = position;
            position += even(jpeg.length);
        }
        if (position > MAX_FILE) {
            throw new MediaException.LimitExceeded("DNG file of " + position + " bytes, larger than 4 GiB");
        }
        raw.longs(324, tileOffsets);
        raw.longs(325, tileCounts);
        if (preview != null) {
            first.longs(273, thumbnailOffset);
            first.longs(330, rawOffset, previewOffset);
            preview.longs(273, jpegOffset);
        }
        var header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        header.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        sink.write(header.array());
        sink.write(first.write(8, 0));
        if (preview != null) {
            sink.write(raw.write(rawOffset, 0));
            sink.write(preview.write(previewOffset, 0));
        }
        if (thumbnail != null) {
            writePadded(sink, thumbnail);
        }
        for (var tile : tiles) {
            writePadded(sink, tile);
        }
        if (jpeg != null) {
            writePadded(sink, jpeg);
        }
    }

    /**
     * Codes the tiles of the raw image.
     *
     * @param content the content whose raw image is coded
     * @return the data of each tile, row by row
     */
    private static byte[][] tiles(DngContent content) {
        var size = content.tileSize;
        var across = Math.ceilDiv(content.width, size);
        var down = Math.ceilDiv(content.height, size);
        var count = across * down;
        var tiles = new byte[count][];
        var work = (long) content.width * content.height * content.channels * SAMPLE_WORK;
        var bands = ParallelBands.bands(count, work);
        var lossless = content.compression == DngCompression.LOSSLESS_JPEG;
        ParallelBands.run(bands, band -> {
            var writer = lossless ? new LosslessJpegWriter() : null;
            var last = ParallelBands.start(band + 1, bands, count);
            for (var tile = ParallelBands.start(band, bands, count); tile < last; tile++) {
                var left = (tile % across) * size;
                var top = (tile / across) * size;
                tiles[tile] = lossless
                        ? writer.encode(content.samples, content.width, content.height, content.channels, left, top,
                        size, size)
                        : uncompressed(content, left, top, size);
            }
        });
        return tiles;
    }

    /**
     * Returns the DNG {@code NewRawImageDigest} of the raw image: the MD5 digest of the MD5 digests of its tiles of
     * 256 by 256 pixels (narrower and shorter at the right and bottom edges), in row order, each over the tile's
     * samples as little-endian 16-bit words, plane by plane and row by row within a plane.
     *
     * @param content the content holding the raw image
     * @return the 16 digest bytes
     */
    static byte[] newRawImageDigest(DngContent content) {
        var spp = content.channels;
        var width = content.width;
        var height = content.height;
        var across = Math.ceilDiv(width, DIGEST_TILE);
        var count = across * Math.ceilDiv(height, DIGEST_TILE);
        var hashes = new byte[count][];
        var bands = ParallelBands.bands(count, (long) width * height * spp * 4);
        ParallelBands.run(bands, band -> {
            var md5 = md5();
            var buffer = new byte[DIGEST_TILE * DIGEST_TILE * spp * 2];
            var last = ParallelBands.start(band + 1, bands, count);
            for (var tile = ParallelBands.start(band, bands, count); tile < last; tile++) {
                var left = (tile % across) * DIGEST_TILE;
                var top = (tile / across) * DIGEST_TILE;
                var columns = Math.min(DIGEST_TILE, width - left);
                var rows = Math.min(DIGEST_TILE, height - top);
                var at = 0;
                for (var plane = 0; plane < spp; plane++) {
                    for (var row = 0; row < rows; row++) {
                        var source = ((top + row) * width + left) * spp + plane;
                        for (var column = 0; column < columns; column++) {
                            var value = content.samples[source + column * spp];
                            buffer[at++] = (byte) value;
                            buffer[at++] = (byte) (value >>> 8);
                        }
                    }
                }
                md5.update(buffer, 0, at);
                hashes[tile] = md5.digest();
            }
        });
        var md5 = md5();
        for (var hash : hashes) {
            md5.update(hash);
        }
        return md5.digest();
    }

    /**
     * Returns an MD5 digest, which every Java runtime provides.
     *
     * @return a new digest
     */
    private static MessageDigest md5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("the Java runtime has no MD5 digest", exception);
        }
    }

    /**
     * Returns the uncompressed data of a tile: 16-bit little-endian samples, the last column and row repeated
     * past the image's edges.
     *
     * @param content the content holding the raw image
     * @param left the left column of the tile
     * @param top the top row of the tile
     * @param size the tile width and height
     * @return the data
     */
    private static byte[] uncompressed(DngContent content, int left, int top, int size) {
        var spp = content.channels;
        var line = size * spp;
        var data = new byte[line * size * 2];
        var available = Math.min(size, content.width - left) * spp;
        for (var row = 0; row < size; row++) {
            var y = Math.min(top + row, content.height - 1);
            var base = (y * content.width + left) * spp;
            var target = row * line * 2;
            for (var k = 0; k < line; k++) {
                var value = content.samples[k < available ? base + k : base + available - spp + k % spp];
                data[target + 2 * k] = (byte) value;
                data[target + 2 * k + 1] = (byte) (value >>> 8);
            }
        }
        return data;
    }

    /**
     * Builds the raw image's directory, with placeholder tile offsets.
     *
     * @param content the content holding the raw image
     * @param tiles the coded tiles, row by row
     * @return the directory
     */
    private static IfdBuilder rawDirectory(DngContent content, byte[][] tiles) {
        var raw = new IfdBuilder(true, false);
        var spp = content.channels;
        for (var tag : content.rawTags) {
            raw.raw(tag.tag(), tag.type(), tag.count(), tag.value());
        }
        raw.longs(254, 0);
        raw.longs(256, content.width);
        raw.longs(257, content.height);
        raw.shorts(258, filled(spp, 16));
        raw.shorts(259, content.compression.code());
        raw.shorts(262, content.cfa != null ? DngParser.PHOTOMETRIC_CFA : DngParser.PHOTOMETRIC_LINEAR_RAW);
        raw.shorts(277, spp);
        raw.shorts(284, 1);
        raw.longs(322, content.tileSize);
        raw.longs(323, content.tileSize);
        raw.longs(324, new long[tiles.length]);
        raw.longs(325, new long[tiles.length]);
        if (content.cfa != null) {
            var cfa = content.cfa;
            raw.shorts(33421, cfa.height(), cfa.width());
            var pattern = new byte[cfa.width() * cfa.height()];
            for (var row = 0; row < cfa.height(); row++) {
                for (var column = 0; column < cfa.width(); column++) {
                    pattern[row * cfa.width() + column] = (byte) cfa.color(row, column).code();
                }
            }
            raw.raw(33422, TiffTypes.BYTE, pattern.length, pattern);
            var planes = new byte[content.planes.size()];
            for (var index = 0; index < planes.length; index++) {
                planes[index] = (byte) content.planes.get(index).code();
            }
            raw.raw(50710, TiffTypes.BYTE, planes.length, planes);
            raw.shorts(50711, content.layout.code());
        }
        raw.longs(50717, longs(filled(spp, 65535)));
        raw.rationals(50718, rationals(new double[]{content.scaleH, content.scaleV}));
        if (content.crop != null) {
            raw.longs(50719, content.crop[0], content.crop[1]);
            raw.longs(50720, content.crop[2], content.crop[3]);
        }
        if (content.userCrop != null) {
            raw.rationals(51125, rationals(content.userCrop));
        }
        if (content.opcodes3 != null) {
            raw.undefined(51022, content.opcodes3);
        }
        return raw;
    }

    /**
     * Adds the entries of a directory of pixels stored in one strip.
     *
     * @param ifd the directory
     * @param width the width in pixels
     * @param height the height in pixels
     * @param compression the TIFF compression
     * @param photometric the TIFF photometric interpretation
     * @param length the strip's length in bytes
     */
    private static void pixels(IfdBuilder ifd, int width, int height, int compression, int photometric, int length) {
        ifd.longs(256, width);
        ifd.longs(257, height);
        ifd.shorts(258, 8, 8, 8);
        ifd.shorts(259, compression);
        ifd.shorts(262, photometric);
        ifd.longs(273, 0);
        ifd.shorts(277, 3);
        ifd.longs(278, height);
        ifd.longs(279, length);
        ifd.rational(282, 72, 1);
        ifd.rational(283, 72, 1);
        ifd.shorts(284, 1);
        ifd.shorts(296, 2);
    }

    /**
     * Adds the file's metadata to the first directory: the EXIF metadata, the descriptive tags, the DNG version
     * and camera model, and the color description.
     *
     * @param first the first directory
     * @param content the content holding the metadata
     * @param backward the {@code DNGBackwardVersion} bytes
     */
    private static void metadata(IfdBuilder first, DngContent content, byte[] backward) {
        for (var tag : content.firstTags) {
            first.raw(tag.tag(), tag.type(), tag.count(), tag.value());
        }
        if (content.exif != null) {
            TiffExifWriter.write(forDng(content.exif), first, true, false);
        }
        if (content.make != null) {
            first.ascii(271, content.make);
        }
        if (content.model != null) {
            first.ascii(272, content.model);
        }
        first.shorts(274, content.orientation);
        first.ascii(305, APPLICATION);
        first.raw(51111, TiffTypes.BYTE, 16, newRawImageDigest(content));
        first.raw(50706, TiffTypes.BYTE, 4, new byte[]{1, 7, 1, 0});
        first.raw(50707, TiffTypes.BYTE, 4, backward);
        first.ascii(50708, content.uniqueCameraModel);
        if (content.cfa == null && content.channels == 1) {
            srational(first, 50730, new double[]{content.baselineExposure});
            return;
        }
        if (content.writeNeutral) {
            first.rationals(50728, rationals(content.neutral));
        } else if (content.asShotWhiteXy != null) {
            first.rationals(50729, rationals(content.asShotWhiteXy));
        }
        if (content.calibration != null) {
            calibration(first, content.calibration, content.baselineExposure);
        } else {
            derived(first, content);
        }
    }

    /**
     * Writes the color description derived from the content's neutral and white-balanced camera to XYZ matrix.
     *
     * @param first the first directory
     * @param content the content holding the neutral and the matrix
     */
    private static void derived(IfdBuilder first, DngContent content) {
        var n = content.neutral.length;
        var forward = forwardMatrix(content.balancedToXyz, n);
        var cameraToXyz = new double[3 * n];
        for (var row = 0; row < 3; row++) {
            for (var column = 0; column < n; column++) {
                cameraToXyz[row * n + column] = forward[row * n + column] / content.neutral[column];
            }
        }
        var xyzToCamera = rightInverse(cameraToXyz, n);
        srational(first, COLOR_MATRIX[0], xyzToCamera);
        first.shorts(ILLUMINANT[0], D50);
        srational(first, FORWARD_MATRIX[0], forward);
        srational(first, 50730, new double[]{content.baselineExposure});
    }

    /**
     * Returns a white-balanced camera to XYZ matrix as a forward matrix, which the DNG specification requires to
     * map a white-balanced neutral (each component 1) to the D50 white: the matrix itself when the white it maps
     * the neutral to is within {@link #WHITE_TOLERANCE} of D50 in each component, and otherwise the matrix with
     * each row scaled so that it maps the neutral to D50 exactly.
     *
     * @param matrix the matrix, three rows of {@code n} columns
     * @param n the number of color planes
     * @return the forward matrix
     * @throws IllegalArgumentException if the matrix maps the neutral to a white with a component that is not
     * positive
     */
    static double[] forwardMatrix(double[] matrix, int n) {
        var white = new double[3];
        var conforms = true;
        for (var row = 0; row < 3; row++) {
            for (var column = 0; column < n; column++) {
                white[row] += matrix[row * n + column];
            }
            if (!(white[row] > 0)) {
                throw new IllegalArgumentException("the camera color matrix maps a neutral to XYZ component " + row
                        + " of " + white[row]);
            }
            conforms &= Math.abs(white[row] - ColorMath.D50_XYZ[row]) <= WHITE_TOLERANCE;
        }
        if (conforms) {
            return matrix;
        }
        var result = matrix.clone();
        for (var row = 0; row < 3; row++) {
            for (var column = 0; column < n; column++) {
                result[row * n + column] *= ColorMath.D50_XYZ[row] / white[row];
            }
        }
        return result;
    }

    /**
     * Writes a color description as it is: the matrices of each calibration illuminant, the illuminants, the
     * camera calibration and its signatures, the analog balance, the baseline exposure and the camera profile's
     * name, tables, tone curve and hints.
     *
     * @param first the first directory
     * @param color the color description
     * @param baselineExposure the baseline exposure, without the profile's exposure offset
     */
    private static void calibration(IfdBuilder first, ColorCalibration color, double baselineExposure) {
        for (var index = 0; index < 3; index++) {
            if (color.colorMatrix[index] == null) {
                continue;
            }
            srational(first, COLOR_MATRIX[index], color.colorMatrix[index]);
            first.shorts(ILLUMINANT[index], color.illuminant[index]);
            if (color.illuminant[index] == OTHER_ILLUMINANT && color.illuminantXy[index] != null) {
                var data = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN);
                data.putShort((short) 0);
                for (var value : rationals(color.illuminantXy[index])) {
                    data.putInt((int) value);
                }
                first.undefined(ILLUMINANT_DATA[index], data.array());
            }
            if (color.forwardMatrix[index] != null) {
                srational(first, FORWARD_MATRIX[index], color.forwardMatrix[index]);
            }
            if (color.calibrationTags[index] != null) {
                srational(first, CAMERA_CALIBRATION[index], color.calibrationTags[index]);
            }
            if (color.hueSatMap[index] != null) {
                first.raw(HUE_SAT_MAP[index], TiffTypes.FLOAT, color.hueSatMap[index].length,
                        floats(color.hueSatMap[index]));
            }
        }
        if (color.cameraSignature != null) {
            first.ascii(50931, color.cameraSignature);
        }
        if (color.profileSignature != null) {
            first.ascii(50932, color.profileSignature);
        }
        if (color.profileName != null) {
            first.ascii(50936, color.profileName);
        }
        if (color.analogBalance != null) {
            first.rationals(50727, rationals(color.analogBalance));
        }
        srational(first, 50730, new double[]{baselineExposure});
        if (color.exposureOffset != 0) {
            srational(first, 51109, new double[]{color.exposureOffset});
        }
        if (color.hueSatDims != null) {
            first.longs(50937, color.hueSatDims[0], color.hueSatDims[1], color.hueSatDims[2]);
            if (color.hueSatSrgb) {
                first.longs(51107, 1);
            }
        }
        if (color.lookTable != null) {
            first.longs(50981, color.lookDims[0], color.lookDims[1], color.lookDims[2]);
            first.raw(50982, TiffTypes.FLOAT, color.lookTable.length, floats(color.lookTable));
            if (color.lookSrgb) {
                first.longs(51108, 1);
            }
        }
        if (color.toneCurve != null) {
            first.raw(50940, TiffTypes.FLOAT, color.toneCurve.length, floats(color.toneCurve));
        }
        if (color.blackRender != 0) {
            first.longs(51110, color.blackRender);
        }
        if (color.gainTableMapData != null) {
            first.undefined(color.gainTableMapSecond ? 52544 : 52525, color.gainTableMapData);
        }
        // TODO: write the semantic masks of the source, so that RGB tables applying to them can be kept too
        if (color.rgbTablesData != null && color.rgbTables.tables.stream().allMatch(table -> table.name() == null)) {
            first.undefined(52543, color.rgbTablesData);
        }
    }

    /**
     * Returns the DNG version the file is compatible back to: 1.1 (lossless JPEG as T.81 specifies it at 16 bits),
     * 1.3 for the CFA layouts 6 to 9, 1.6 for a calibration illuminant given by its chromaticity, and the version
     * of every required opcode of opcode list 3.
     *
     * @param content the content written
     * @return the four version bytes
     */
    static byte[] backwardVersion(DngContent content) {
        var version = 0x01010000;
        if (content.cfa != null && content.layout.code() >= 6) {
            version = Math.max(version, 0x01030000);
        }
        var color = content.calibration;
        if (color != null && (color.illuminant[0] == OTHER_ILLUMINANT || color.illuminant[1] == OTHER_ILLUMINANT)) {
            version = Math.max(version, 0x01060000);
        }
        if (content.opcodes3 != null) {
            version = Math.max(version, 0x01030000);
            var list = ByteBuffer.wrap(content.opcodes3);
            if (list.remaining() >= 4) {
                var count = list.getInt();
                for (var index = 0; index < count && list.remaining() >= 16; index++) {
                    list.getInt();
                    var required = list.getInt();
                    var flags = list.getInt();
                    var length = list.getInt();
                    if ((flags & 1) == 0) {
                        version = Integer.compareUnsigned(required, version) > 0 ? required : version;
                    }
                    if (length < 0 || length > list.remaining()) {
                        break;
                    }
                    list.position(list.position() + length);
                }
            }
        }
        return new byte[]{(byte) (version >>> 24), (byte) (version >>> 16), (byte) (version >>> 8), (byte) version};
    }

    /**
     * Returns EXIF metadata as a DNG file holds it: without the entries of its first directory in the range of
     * DNG tags, which the writer owns, and with two values cameras commonly write loosely put in the form EXIF 2.32
     * gives them: a {@code LensSpecification} element of 0 becomes 0/0, EXIF's unknown value, and a
     * {@code UserComment} of printable ASCII text with the undefined or an unknown character code gets the ASCII
     * code, its text kept.
     *
     * @param exif the metadata
     * @return the metadata to write
     */
    static ExifData forDng(ExifData exif) {
        var ifd0 = new LinkedHashMap<>(exif.ifd0());
        ifd0.keySet().removeIf(tag -> tag >= FIRST_DNG_TAG && tag <= LAST_DNG_TAG);
        var entries = new LinkedHashMap<>(exif.exif());
        if (entries.get(LENS_SPECIFICATION) instanceof ExifValue.Rationals lens) {
            var numerators = lens.numerators().clone();
            var denominators = lens.denominators().clone();
            for (var index = 0; index < numerators.length; index++) {
                if (numerators[index] == 0) {
                    denominators[index] = 0;
                }
            }
            entries.put(LENS_SPECIFICATION, new ExifValue.Rationals(lens.type(), numerators, denominators));
        }
        if (entries.get(USER_COMMENT) instanceof ExifValue.Undefined comment && comment.bytes().length >= 8) {
            var bytes = comment.bytes().clone();
            var code = new String(bytes, 0, 8, StandardCharsets.ISO_8859_1);
            var ascii = true;
            for (var index = 8; index < bytes.length && ascii; index++) {
                ascii = bytes[index] == 0 || (bytes[index] >= 0x20 && bytes[index] < 0x7F);
            }
            if (!DEFINED_CODES.contains(code) && ascii) {
                System.arraycopy(ASCII_CODE, 0, bytes, 0, 8);
                entries.put(USER_COMMENT, new ExifValue.Undefined(bytes));
            }
        }
        return new ExifData(ifd0, entries, exif.gps(), exif.interop(), Map.of(), Optional.empty());
    }

    /**
     * Returns the size of a picture scaled down to fit a square.
     *
     * @param width the width in pixels
     * @param height the height in pixels
     * @param side the side of the square
     * @return the width and height, each at least 1
     */
    static int[] fit(int width, int height, int side) {
        var longest = Math.max(width, height);
        if (longest <= side) {
            return new int[]{width, height};
        }
        return new int[]{Math.max(1, (int) Math.round((double) width * side / longest)),
                Math.max(1, (int) Math.round((double) height * side / longest))};
    }

    /**
     * Returns the RGB samples of a picture, row by row.
     *
     * @param picture the 8-bit picture
     * @return three bytes per pixel
     */
    private static byte[] rgb(RgbaPicture picture) {
        var width = picture.width();
        var height = picture.height();
        var pixels = picture.pixels();
        var out = new byte[width * height * 3];
        for (var y = 0; y < height; y++) {
            var source = y * picture.stride();
            var target = y * width * 3;
            for (var x = 0; x < width; x++) {
                out[target + 3 * x] = pixels[source + 4 * x];
                out[target + 3 * x + 1] = pixels[source + 4 * x + 1];
                out[target + 3 * x + 2] = pixels[source + 4 * x + 2];
            }
        }
        return out;
    }

    /**
     * Returns the right inverse of a {@code 3 x n} matrix of full row rank: its inverse for {@code n = 3}, the
     * pseudo-inverse {@code M^T (M M^T)^-1} otherwise.
     *
     * @param m the matrix, row by row
     * @param n the number of columns, 3 or 4
     * @return the {@code n x 3} matrix
     * @throws IllegalArgumentException if the matrix is singular
     */
    static double[] rightInverse(double[] m, int n) {
        var gram = new double[9];
        for (var i = 0; i < 3; i++) {
            for (var j = 0; j < 3; j++) {
                for (var k = 0; k < n; k++) {
                    gram[i * 3 + j] += m[i * n + k] * m[j * n + k];
                }
            }
        }
        var inverse = ColorMath.invert(gram, 3);
        if (inverse == null) {
            throw new IllegalArgumentException("the camera color matrix is singular");
        }
        var result = new double[n * 3];
        for (var k = 0; k < n; k++) {
            for (var j = 0; j < 3; j++) {
                var sum = 0.0;
                for (var i = 0; i < 3; i++) {
                    sum += m[i * n + k] * inverse[i * 3 + j];
                }
                result[k * 3 + j] = sum;
            }
        }
        return result;
    }

    /**
     * Adds an SRATIONAL entry.
     *
     * @param ifd the directory
     * @param tag the tag number
     * @param values the values, finite
     */
    private static void srational(IfdBuilder ifd, int tag, double[] values) {
        var parts = srationals(values);
        var bytes = ByteBuffer.allocate(parts.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (var part : parts) {
            bytes.putInt((int) part);
        }
        ifd.raw(tag, TiffTypes.SRATIONAL, values.length, bytes.array());
    }

    /**
     * Returns signed rationals approximating values, each with the largest power of ten denominator, at most
     * 10^9, whose numerator fits.
     *
     * @param values the values, finite
     * @return the numerator and denominator of each value, alternating
     */
    static long[] srationals(double[] values) {
        var result = new long[values.length * 2];
        for (var index = 0; index < values.length; index++) {
            var value = values[index];
            long denominator = 1_000_000_000L;
            while (denominator > 1 && Math.abs(value) * denominator > Integer.MAX_VALUE) {
                denominator /= 10;
            }
            var numerator = Math.clamp(Math.round(value * denominator), Integer.MIN_VALUE, Integer.MAX_VALUE);
            result[2 * index] = numerator & 0xFFFF_FFFFL;
            result[2 * index + 1] = denominator;
        }
        return result;
    }

    /**
     * Returns unsigned rationals approximating values, each with the largest power of ten denominator, at most
     * 10^9, whose numerator fits.
     *
     * @param values the values, finite and not negative
     * @return the numerator and denominator of each value, alternating
     */
    static long[] rationals(double[] values) {
        var result = new long[values.length * 2];
        for (var index = 0; index < values.length; index++) {
            var value = Math.max(0, values[index]);
            long denominator = 1_000_000_000L;
            while (denominator > 1 && value * denominator > 0xFFFF_FFFFL) {
                denominator /= 10;
            }
            result[2 * index] = Math.min(Math.round(value * denominator), 0xFFFF_FFFFL);
            result[2 * index + 1] = denominator;
        }
        return result;
    }

    /**
     * Encodes floats little-endian.
     *
     * @param values the values to encode
     * @return four bytes per value
     */
    private static byte[] floats(float[] values) {
        var buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (var value : values) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    /**
     * Returns an array of one value repeated.
     *
     * @param count the length
     * @param value the value repeated
     * @return the array
     */
    private static int[] filled(int count, int value) {
        var result = new int[count];
        Arrays.fill(result, value);
        return result;
    }

    /**
     * Widens integers.
     *
     * @param values the values to widen
     * @return the values as longs
     */
    private static long[] longs(int[] values) {
        var result = new long[values.length];
        for (var index = 0; index < values.length; index++) {
            result[index] = values[index];
        }
        return result;
    }

    /**
     * Rounds a length up to an even number.
     *
     * @param length a length in bytes
     * @return the even length
     */
    private static long even(long length) {
        return length + (length & 1);
    }

    /**
     * Writes data followed by a zero byte when its length is odd.
     *
     * @param sink the output
     * @param data the bytes to write
     */
    private static void writePadded(MediaSink sink, byte[] data) {
        sink.write(data);
        if ((data.length & 1) != 0) {
            sink.write(new byte[1]);
        }
    }
}
