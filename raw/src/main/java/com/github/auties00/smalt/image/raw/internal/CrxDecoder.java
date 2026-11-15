package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;

/**
 * Decodes the CRX-coded sensor data of a Canon CR3 file: the lossless form, whose four color planes
 * are coded directly, and the lossy C-RAW form, whose planes are coded as the subbands of a three
 * level 5/3 wavelet transform.
 *
 * <p>The {@code CMP1} box of the image's track gives the image and tile sizes, the bit depth, the
 * color filter layout and the number of wavelet levels; the sample starts with one header per tile
 * ({@code 0xFF01}, or {@code 0xFF11} in version 2), color plane ({@code 0xFF02} or {@code 0xFF12})
 * and subband ({@code 0xFF03} or {@code 0xFF13}) giving the length of every bitstream. The planes
 * hold the red, the two green and the blue photosites of each 2x2 cell in that order, placed as the
 * filter layout says.
 *
 * <p>Every subband is coded in raster order with an adaptive Golomb-Rice code and a run mode, in
 * the manner of JPEG-LS: the low band (and the lossless planes) predicts each value with the median
 * edge detector of its left, upper and upper-left neighbors, the high bands predict zero and enter
 * the run mode only over zeros. A lossy plane is the integer 5/3 synthesis of its dequantized
 * subbands; a tile carries the coefficients its neighbors' transforms need on its edges, and the
 * transform of each tile starts its own phase at its left edge. Version 1 quantizes each subband with
 * one step; version 2 codes a quantization map per tile, one value per four columns of the first
 * level, from which each subband's steps follow.
 *
 * @implNote This implementation was derived without reference code: the box and header layout from
 * Laurent Clevy's public CR3 description and the 3-level wavelet and Golomb-Rice parameter update
 * from Canon's patent US 2016/0323602 A1; every other detail was worked out against the sensor
 * values Adobe DNG Converter writes for public-domain samples, which this decoder reproduces exactly.
 */
final class CrxDecoder {
    /**
     * The block length exponents of the run mode, indexed by the run index (JPEG-LS {@code J}).
     */
    private static final int[] RUN_BITS = {0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8,
            9, 10, 11, 12, 13, 14, 15};

    /**
     * The quantization scale of each sixth of an octave, in 64ths (the {@code levelScale} of HEVC).
     */
    private static final int[] LEVEL_SCALE = {40, 45, 51, 57, 64, 72};

    /**
     * The number of leading zeros from which a Golomb-Rice code is escaped.
     */
    private static final int ESCAPE_ZEROS = 41;

    /**
     * The bits of an escaped symbol.
     */
    private static final int ESCAPE_BITS = 21;

    /**
     * The largest Golomb-Rice parameter.
     */
    private static final int MAX_K = 15;

    /**
     * The coder of the lossless planes and of the low band: median prediction and run mode.
     */
    private static final int LOW = 0;

    /**
     * The coder of the high bands: zero prediction and run mode over zeros.
     */
    private static final int HIGH = 1;

    /**
     * The coder of a quantization map: median prediction without run mode.
     */
    private static final int MAP = 2;

    /**
     * The value of the quantization map that maps to the scale of {@code LEVEL_SCALE[0]}.
     */
    private static final int MAP_ORIGIN = 302;

    /**
     * The most quantization map values on either side of {@link #MAP_ORIGIN}.
     */
    private static final int MAP_RANGE = 96;

    /**
     * The largest version 1 quantization parameter.
     */
    private static final int MAX_QP = 120;

    /**
     * Where each color plane's photosites lie in a 2x2 cell (row times two plus column), by color
     * filter layout; the planes hold red, the green of the first row, the green of the second row
     * and blue.
     */
    private static final int[][] CELLS = {{0, 1, 2, 3}, {1, 0, 3, 2}, {2, 0, 3, 1}, {3, 1, 2, 0}};

    /**
     * The most tiles of an image.
     */
    private static final int MAX_TILES = 256;

    /**
     * The geometry and coding parameters of a CRX image, from its {@code CMP1} box.
     *
     * @param version the codec version, {@code 0x100} or {@code 0x200}
     * @param width the image width in photosites
     * @param height the image height in photosites
     * @param tileWidth the tile width in photosites
     * @param tileHeight the tile height in photosites
     * @param bits the bits of each photosite
     * @param cfa the color filter layout: 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR
     * @param levels the number of wavelet levels, 0 for lossless data
     * @param headerLength the length of the tile, plane and subband headers at the start of the sample
     */
    record Image(int version, int width, int height, int tileWidth, int tileHeight, int bits, int cfa, int levels,
                 int headerLength) {
        /**
         * Returns the number of tile columns.
         *
         * @return the tile columns
         */
        int columns() {
            return (width + tileWidth - 1) / tileWidth;
        }

        /**
         * Returns the number of tile rows.
         *
         * @return the tile rows
         */
        int rows() {
            return (height + tileHeight - 1) / tileHeight;
        }

        /**
         * Returns the color filter pattern the layout describes.
         *
         * @return the pattern, such as {@code RGGB}
         */
        String pattern() {
            return switch (cfa) {
                case 0 -> "RGGB";
                case 1 -> "GRBG";
                case 2 -> "GBRG";
                default -> "BGGR";
            };
        }
    }

    /**
     * The bitstream of one subband or lossless plane.
     *
     * @param offset the offset of the bitstream in the sample
     * @param length the length of the bitstream
     * @param quantizer the version 1 quantization parameter, or the version 2 scale in eighths
     * @param exact whether the version 2 subband is not quantized
     */
    private record Band(int offset, int length, int quantizer, boolean exact) {
    }

    /**
     * The bitstreams of one tile.
     *
     * @param index the tile index in raster order
     * @param mapOffset the offset of the quantization map, version 2 only
     * @param mapLength the length of the quantization map, 0 when it has none
     * @param planes the bitstreams of each color plane
     */
    private record Tile(int index, int mapOffset, int mapLength, Band[][] planes) {
    }

    /**
     * Prevents instantiation.
     */
    private CrxDecoder() {
        throw new AssertionError();
    }

    /**
     * Reads the body of a {@code CMP1} box.
     *
     * @param body the box body, after its size and type
     * @return the image description
     * @throws MediaException.Malformed if the box is truncated or inconsistent
     * @throws MediaException.Unsupported if the image uses a coding no sample has shown
     */
    static Image image(byte[] body) {
        if (body.length < 32) {
            throw new MediaException.Malformed("CR3 CMP1 box too short");
        }
        var version = u16(body, 4);
        var width = u32(body, 8);
        var height = u32(body, 12);
        var tileWidth = u32(body, 16);
        var tileHeight = u32(body, 20);
        var bits = body[24] & 0xFF;
        var planes = (body[25] & 0xFF) >> 4;
        var cfa = body[25] & 15;
        var encoding = (body[26] & 0xFF) >> 4;
        var levels = body[26] & 15;
        var headerLength = u32(body, 28);
        if (version != 0x100 && version != 0x200) {
            throw new MediaException.Unsupported("cr3", "CRX version " + Integer.toHexString(version));
        }
        if (encoding != 0) {
            // TODO: decode encoding type 3 (the YCbCr frames of raw burst rolls): no sample is available
            throw new MediaException.Unsupported("cr3", "CRX encoding type " + encoding);
        }
        if (planes != 4) {
            throw new MediaException.Unsupported("cr3", "CRX images of " + planes + " color planes");
        }
        if (levels != 0 && levels != 3) {
            // TODO: decode other wavelet depths if a camera uses one: no sample has shown one
            throw new MediaException.Unsupported("cr3", "CRX images of " + levels + " wavelet levels");
        }
        if (width <= 0 || height <= 0 || tileWidth <= 0 || tileHeight <= 0 || tileWidth > width
                || tileHeight > height || cfa > 3 || bits < 8 || bits > 16 || headerLength < 0) {
            throw new MediaException.Malformed("inconsistent CR3 CMP1 box");
        }
        var image = new Image(version, width, height, tileWidth, tileHeight, bits, cfa, levels, headerLength);
        if ((long) image.columns() * image.rows() > MAX_TILES || (tileWidth & 1) != 0 || (tileHeight & 1) != 0
                || (width & 1) != 0 || (height & 1) != 0) {
            throw new MediaException.Malformed("inconsistent CR3 CMP1 tiles");
        }
        return image;
    }

    /**
     * Decodes the sensor data of an image.
     *
     * @param image the image description
     * @param sample the sample holding the image's headers and bitstreams
     * @param plane the plane to fill, of the image's size
     * @param limits the resource bounds
     * @throws MediaException.Malformed if the data is damaged
     * @throws MediaException.Unsupported if the data uses a coding no sample has shown
     */
    static void decode(Image image, byte[] sample, SensorPlane plane, MediaLimits limits) {
        var tiles = tiles(image, sample);
        if (image.levels() == 0) {
            var count = tiles.length * 4;
            ParallelBands.run(count, index -> {
                var tile = tiles[index / 4];
                lossless(image, sample, tile, index % 4, plane);
            });
            return;
        }
        for (var tile : tiles) {
            var column = tile.index() % image.columns();
            var row = tile.index() / image.columns();
            var horizontal = new Axis(tileExtent(image.width(), image.tileWidth(), column) / 2, image.levels(), column > 0,
                    column + 1 < image.columns());
            var vertical = new Axis(tileExtent(image.height(), image.tileHeight(), row) / 2, image.levels(), row > 0,
                    row + 1 < image.rows());
            var map = image.version() == 0x200 ? map(sample, tile, horizontal, vertical) : null;
            for (var index = 0; index < 4; index++) {
                wavelet(image, sample, tile, index, horizontal, vertical, map, plane, limits);
            }
        }
    }

    /**
     * Returns the extent of a tile.
     *
     * @param size the image extent
     * @param tile the nominal tile extent
     * @param index the tile index along the axis
     * @return the tile extent
     */
    private static int tileExtent(int size, int tile, int index) {
        return Math.min(tile, size - index * tile);
    }

    /**
     * Reads the tile, plane and subband headers.
     *
     * @param image the image description
     * @param sample the CRX sample, its headers followed by its bitstreams
     * @return the tiles in raster order
     * @throws MediaException.Malformed if the headers are damaged
     * @throws MediaException.Unsupported if a plane header sets a field no sample has set
     */
    private static Tile[] tiles(Image image, byte[] sample) {
        var second = image.version() == 0x200;
        var tileMarker = second ? 0xFF11 : 0xFF01;
        var planeMarker = second ? 0xFF12 : 0xFF02;
        var bandMarker = second ? 0xFF13 : 0xFF03;
        var bandsPerPlane = image.levels() == 0 ? 1 : 3 * image.levels() + 1;
        var count = image.columns() * image.rows();
        var end = image.headerLength();
        if (end > sample.length) {
            throw new MediaException.Malformed("CRX headers past the end of the sample");
        }
        var tiles = new Tile[count];
        var position = 0;
        var offset = (long) end;
        for (var t = 0; t < count; t++) {
            var length = header(sample, position, end, tileMarker, second ? 16 : 8);
            var mapOffset = 0;
            var mapLength = 0;
            if (second) {
                mapLength = u32(sample, position + 12);
                var padding = u16(sample, position + 16);
                if (mapLength < 0) {
                    throw new MediaException.Malformed("damaged CRX tile header");
                }
                mapOffset = (int) offset;
                offset += (long) mapLength + padding;
            }
            position += 4 + length;
            var planes = new Band[4][bandsPerPlane];
            for (var p = 0; p < 4; p++) {
                var planeLength = header(sample, position, end, planeMarker, 8);
                var planeSize = u32(sample, position + 4) & 0xFFFFFFFFL;
                var rounded = (u32(sample, position + 8) >>> 25) & 3;
                if (rounded != 0) {
                    // TODO: decode planes whose header sets its rounded bits field: every sample leaves it 0
                    throw new MediaException.Unsupported("cr3", "CRX planes of " + rounded + " rounded bits");
                }
                position += 4 + planeLength;
                var planeStart = offset;
                for (var b = 0; b < bandsPerPlane; b++) {
                    var bandLength = header(sample, position, end, bandMarker, second ? 16 : 8);
                    var size = u32(sample, position + 4) & 0xFFFFFFFFL;
                    var word = u32(sample, position + 8);
                    int quantizer;
                    boolean exact;
                    if (second) {
                        quantizer = word & 0xFFFF;
                        exact = u32(sample, position + 12) != 0 || quantizer == 0;
                    } else {
                        quantizer = (word >>> 19) & 0xFF;
                        exact = false;
                    }
                    if (offset + size > sample.length) {
                        throw new MediaException.Malformed("CRX bitstream past the end of the sample");
                    }
                    planes[p][b] = new Band((int) offset, (int) size, quantizer, exact);
                    offset += size;
                    position += 4 + bandLength;
                }
                if (offset - planeStart > planeSize) {
                    throw new MediaException.Malformed("CRX subbands longer than their plane");
                }
                offset = planeStart + planeSize;
            }
            if (offset > sample.length) {
                throw new MediaException.Malformed("CRX plane past the end of the sample");
            }
            tiles[t] = new Tile(t, mapOffset, mapLength, planes);
        }
        return tiles;
    }

    /**
     * Checks one header and returns the length of its body.
     *
     * @param sample the CRX sample, its headers followed by its bitstreams
     * @param position the offset of the header
     * @param end the end of the headers
     * @param marker the expected marker
     * @param minimum the smallest body length
     * @return the body length
     * @throws MediaException.Malformed if the header is not the expected one
     */
    private static int header(byte[] sample, int position, int end, int marker, int minimum) {
        if (position + 4 > end || u16(sample, position) != marker) {
            throw new MediaException.Malformed("missing CRX header " + Integer.toHexString(marker));
        }
        var length = u16(sample, position + 2);
        if (length < minimum || position + 4 + length > end) {
            throw new MediaException.Malformed("damaged CRX header " + Integer.toHexString(marker));
        }
        return length;
    }

    /**
     * Decodes one lossless color plane of a tile into the sensor plane.
     *
     * @param image the image description
     * @param sample the CRX sample, its headers followed by its bitstreams
     * @param tile the tile's bitstreams
     * @param index the color plane
     * @param plane the sensor plane
     */
    private static void lossless(Image image, byte[] sample, Tile tile, int index, SensorPlane plane) {
        var column = tile.index() % image.columns();
        var row = tile.index() / image.columns();
        var width = tileExtent(image.width(), image.tileWidth(), column) / 2;
        var height = tileExtent(image.height(), image.tileHeight(), row) / 2;
        var band = tile.planes()[index][0];
        var bits = new Bits(sample, band.offset(), band.offset() + band.length());
        var rows = new int[2 * width];
        var coder = new Coder(bits, width, LOW, 1 << (image.bits() - 1));
        var maximum = (1 << image.bits()) - 1;
        var cell = cell(image.cfa(), index);
        var x0 = column * image.tileWidth() + (cell & 1);
        var y0 = row * image.tileHeight() + (cell >> 1);
        for (var y = 0; y < height; y++) {
            var current = (y & 1) * width;
            coder.row(rows, current, y == 0 ? -1 : width - current, y);
            var at = (y0 + 2 * y) * image.width() + x0;
            for (var x = 0; x < width; x++) {
                plane.integers[at + 2 * x] = (short) Math.clamp(rows[current + x], 0, maximum);
            }
        }
        bits.check();
    }

    /**
     * Returns where a color plane's photosites lie in each 2x2 cell.
     *
     * @param cfa the color filter layout
     * @param index the color plane: red, green, green, blue
     * @return the row times two plus the column of the photosite in the cell
     */
    private static int cell(int cfa, int index) {
        return CELLS[cfa][index];
    }

    /**
     * Decodes a version 2 quantization map.
     *
     * @param sample the CRX sample, its headers followed by its bitstreams
     * @param tile the tile's bitstreams
     * @param horizontal the horizontal geometry of the tile
     * @param vertical the vertical geometry of the tile
     * @return the map, one value per four columns and per row of the first level
     * @throws MediaException.Malformed if the tile has no map or it is damaged
     */
    private static Map map(byte[] sample, Tile tile, Axis horizontal, Axis vertical) {
        if (tile.mapLength() < 4) {
            throw new MediaException.Malformed("CRX tile without quantization map");
        }
        var width = (horizontal.lows[1] + 3) / 4;
        var height = vertical.lows[1];
        var bits = new Bits(sample, tile.mapOffset(), tile.mapOffset() + tile.mapLength());
        var first = bits.read(32);
        var values = new int[width * height];
        var coder = new Coder(bits, width, MAP, first);
        var rows = new int[2 * width];
        for (var y = 0; y < height; y++) {
            var current = (y & 1) * width;
            coder.row(rows, current, y == 0 ? -1 : width - current, y);
            System.arraycopy(rows, current, values, y * width, width);
        }
        bits.check();
        return new Map(values, width, height);
    }

    /**
     * A version 2 quantization map.
     *
     * @param values the values in raster order
     * @param width the values per row
     * @param height the rows
     */
    private record Map(int[] values, int width, int height) {
        /**
         * Returns the step of a version 2 quantization map value.
         *
         * @param value the map value
         * @param scale the subband's scale, in eighths
         * @return the step
         * @throws MediaException.Malformed if the value is out of range or gives a step of zero
         */
        static int step(int value, int scale) {
            var offset = value - MAP_ORIGIN;
            if (offset < -MAP_RANGE || offset > MAP_RANGE) {
                throw new MediaException.Malformed("CRX quantization map value out of range: " + value);
            }
            var octave = Math.floorDiv(offset, 6);
            var base = (long) LEVEL_SCALE[Math.floorMod(offset, 6)];
            base = octave >= 0 ? base << octave : base >> -octave;
            var step = Math.min(Integer.MAX_VALUE, 2 * base / 5 * scale >> 3);
            if (step == 0) {
                throw new MediaException.Malformed("CRX quantization map value gives no step: " + value);
            }
            return (int) step;
        }
    }

    /**
     * Returns the step of a version 1 quantization parameter.
     *
     * @param quantizer the parameter
     * @return the step
     * @throws MediaException.Malformed if the parameter is out of range or gives a step of zero
     */
    private static int step(int quantizer) {
        var step = quantizer > MAX_QP ? 0 : (int) (((long) LEVEL_SCALE[quantizer % 6] << (quantizer / 6)) >> 6);
        if (step == 0) {
            throw new MediaException.Malformed("CRX quantization parameter out of range: " + quantizer);
        }
        return step;
    }

    /**
     * Decodes one lossy color plane of a tile into the sensor plane.
     *
     * @param image the image description
     * @param sample the CRX sample, its headers followed by its bitstreams
     * @param tile the tile's bitstreams
     * @param index the color plane
     * @param horizontal the horizontal geometry of the tile
     * @param vertical the vertical geometry of the tile
     * @param map the version 2 quantization map, or {@code null}
     * @param plane the sensor plane
     * @param limits the resource bounds
     */
    private static void wavelet(Image image, byte[] sample, Tile tile, int index, Axis horizontal, Axis vertical,
                                Map map, SensorPlane plane, MediaLimits limits) {
        var levels = image.levels();
        var bands = tile.planes()[index];
        var coefficients = new int[bands.length][];
        LimitChecks.checkAllocation(limits, 8L * horizontal.lows[1] * vertical.lows[1] * 4);
        ParallelBands.run(bands.length, b -> {
            var level = b == 0 ? levels : levels - (b - 1) / 3;
            var kind = b == 0 ? 0 : (b - 1) % 3 + 1;
            var width = kind == 0 || kind == 2 ? horizontal.lows[level] : horizontal.highs[level];
            var height = kind == 0 || kind == 1 ? vertical.lows[level] : vertical.highs[level];
            var band = bands[b];
            var values = new int[width * height];
            var bits = new Bits(sample, band.offset(), band.offset() + band.length());
            var coder = new Coder(bits, width, kind == 0 ? LOW : HIGH, 1 << (image.bits() - 1));
            var rows = new int[2 * width];
            for (var y = 0; y < height; y++) {
                var current = (y & 1) * width;
                coder.row(rows, current, y == 0 ? -1 : width - current, y);
                System.arraycopy(rows, current, values, y * width, width);
            }
            bits.check();
            dequantize(values, width, height, band, level, map);
            coefficients[b] = values;
        });
        var low = coefficients[0];
        var maximum = (1 << image.bits()) - 1;
        var cell = cell(image.cfa(), index);
        var column = tile.index() % image.columns();
        var row = tile.index() / image.columns();
        var x0 = column * image.tileWidth() + (cell & 1);
        var y0 = row * image.tileHeight() + (cell >> 1);
        for (var level = levels; level >= 1; level--) {
            var first = 1 + 3 * (levels - level);
            var outWidth = level > 1 ? horizontal.lows[level - 1] : horizontal.own[0];
            var outHeight = level > 1 ? vertical.lows[level - 1] : vertical.own[0];
            low = synthesize(low, coefficients[first], coefficients[first + 1], coefficients[first + 2],
                    horizontal, vertical, level, outWidth, outHeight, level == 1 ? plane : null, image.width(), x0, y0,
                    maximum);
            coefficients[first] = null;
            coefficients[first + 1] = null;
            coefficients[first + 2] = null;
        }
    }

    /**
     * Multiplies the quantized coefficients of a subband by their steps.
     *
     * @param values the coefficients, replaced
     * @param width the subband width
     * @param height the subband height
     * @param band the subband's bitstream
     * @param level the subband's level
     * @param map the version 2 quantization map, or {@code null} for version 1
     */
    private static void dequantize(int[] values, int width, int height, Band band, int level, Map map) {
        if (map == null) {
            var step = step(band.quantizer());
            if (step != 1) {
                for (var i = 0; i < values.length; i++) {
                    values[i] *= step;
                }
            }
            return;
        }
        if (band.exact()) {
            return;
        }
        if (level > 2) {
            // TODO: dequantize the level 3 subbands of version 2 if a camera quantizes them: every sample leaves them
            //  exact, so the map's resolution at that level is unknown
            throw new MediaException.Unsupported("cr3", "quantized CRX subbands at level " + level);
        }
        var steps = new int[map.width()];
        for (var y = 0; y < height; y++) {
            if (level == 1) {
                var at = Math.min(y, map.height() - 1) * map.width();
                for (var x = 0; x < map.width(); x++) {
                    steps[x] = Map.step(map.values()[at + x], band.quantizer());
                }
            } else {
                var upper = Math.min(2 * y, map.height() - 1) * map.width();
                var lower = Math.min(2 * y + 1, map.height() - 1) * map.width();
                for (var x = 0; x < map.width(); x++) {
                    steps[x] = Map.step((map.values()[upper + x] + map.values()[lower + x]) >> 1, band.quantizer());
                }
            }
            var shift = level == 1 ? 2 : 1;
            var at = y * width;
            for (var x = 0; x < width; x++) {
                values[at + x] *= steps[Math.min(x >> shift, map.width() - 1)];
            }
        }
    }

    /**
     * Computes the inverse transform of one level.
     *
     * @param low the low band, {@code horizontal.lows[level]} by {@code vertical.lows[level]}
     * @param lowHigh the band high horizontally and low vertically
     * @param highLow the band low horizontally and high vertically
     * @param highHigh the band high in both directions
     * @param horizontal the horizontal geometry
     * @param vertical the vertical geometry
     * @param level the wavelet level, from 1 to 3
     * @param outWidth the output width
     * @param outHeight the output height
     * @param plane the sensor plane the last level writes into, or {@code null} to return the output
     * @param stride the sensor plane width
     * @param x0 the column of the plane's first photosite in the sensor plane
     * @param y0 the row of the plane's first photosite in the sensor plane
     * @param maximum the largest photosite value
     * @return the output, or {@code null} when written into the sensor plane
     */
    private static int[] synthesize(int[] low, int[] lowHigh, int[] highLow, int[] highHigh, Axis horizontal,
                                    Axis vertical, int level, int outWidth, int outHeight, SensorPlane plane,
                                    int stride, int x0, int y0, int maximum) {
        var lows = horizontal.lows[level];
        var highs = horizontal.highs[level];
        var lowRows = vertical.lows[level];
        var highRows = vertical.highs[level];
        var rows = lowRows + highRows;
        var columns = new int[rows * outWidth];
        var before = horizontal.before;
        Lift.check(lows, highs, before, outWidth);
        Lift.check(lowRows, highRows, vertical.before, outHeight);
        var bands = ParallelBands.bands(rows, (long) rows * outWidth);
        ParallelBands.run(bands, band -> {
            var start = ParallelBands.start(band, bands, rows);
            var stop = ParallelBands.start(band + 1, bands, rows);
            for (var y = start; y < stop; y++) {
                if (y < lowRows) {
                    Lift.line(low, y * lows, lows, lowHigh, y * highs, highs, before, columns, y * outWidth, outWidth);
                } else {
                    var r = y - lowRows;
                    Lift.line(highLow, r * lows, lows, highHigh, r * highs, highs, before, columns, y * outWidth, outWidth);
                }
            }
        });
        var out = plane == null ? new int[outWidth * outHeight] : null;
        var strips = ParallelBands.bands(outWidth, (long) rows * outWidth);
        var verticalBefore = vertical.before;
        ParallelBands.run(strips, band -> {
            var start = ParallelBands.start(band, strips, outWidth);
            var stop = ParallelBands.start(band + 1, strips, outWidth);
            Lift.rows(columns, outWidth, start, stop, lowRows, highRows, verticalBefore);
            for (var y = 0; y < outHeight; y++) {
                var source = Lift.rowOf(y, lowRows, verticalBefore) * outWidth;
                if (out != null) {
                    System.arraycopy(columns, source + start, out, y * outWidth + start, stop - start);
                } else {
                    var at = (y0 + 2 * y) * stride + x0;
                    for (var x = start; x < stop; x++) {
                        plane.integers[at + 2 * x] = (short) Math.clamp(columns[source + x], 0, maximum);
                    }
                }
            }
        });
        return out;
    }

    /**
     * The integer 5/3 synthesis of one dimension, in place: the low samples sit at the even positions
     * from zero, the high samples at the odd ones, preceded by one at position -1 when the tile has a
     * neighbor before it; the samples beyond the coded ones are mirrored.
     */
    private static final class Lift {
        /**
         * Prevents instantiation.
         */
        private Lift() {
            throw new AssertionError();
        }

        /**
         * Returns the last coded position.
         *
         * @param lows the low samples
         * @param highs the high samples
         * @param before whether the first high sample precedes position 0
         * @return the last position
         */
        static int last(int lows, int highs, boolean before) {
            var after = before ? highs - 1 : highs;
            return Math.max(2 * (lows - 1), 2 * after - 1);
        }

        /**
         * Checks that the coded samples cover the output.
         *
         * @param lows the low samples
         * @param highs the high samples
         * @param before whether the first high sample precedes position 0
         * @param count the output samples
         * @throws MediaException.Malformed if they do not
         */
        static void check(int lows, int highs, boolean before, int count) {
            var after = before ? highs - 1 : highs;
            if (lows < 1 || after < Math.max(1, lows - 1) || after > lows || count < 1
                    || last(lows, highs, before) < count - 1) {
                throw new MediaException.Malformed("inconsistent CRX subband sizes");
            }
        }

        /**
         * Returns the index, among the high samples, of an odd position or of position -1.
         *
         * @param position a position along the line, -1 being the high sample before the tile
         * @param before whether the first high sample precedes position 0
         * @return the index
         */
        static int high(int position, boolean before) {
            return before ? (position + 1) >> 1 : (position - 1) >> 1;
        }

        /**
         * Mirrors a position into the coded range.
         *
         * @param position a position along the line, -1 being the high sample before the tile
         * @param first the first coded position
         * @param last the last coded position
         * @return the mirrored position
         */
        static int mirror(int position, int first, int last) {
            if (position < first) {
                return 2 * first - position;
            }
            return position > last ? 2 * last - position : position;
        }

        /**
         * Synthesizes one line.
         *
         * @param low the low samples' array, modified
         * @param lowAt the first low sample
         * @param lows the low samples
         * @param high the high samples' array, modified
         * @param highAt the first high sample
         * @param highs the high samples
         * @param before whether the first high sample precedes position 0
         * @param out the output array
         * @param outAt the first output sample
         * @param count the output samples
         */
        static void line(int[] low, int lowAt, int lows, int[] high, int highAt, int highs, boolean before, int[] out,
                         int outAt, int count) {
            var first = before ? -1 : 0;
            var last = last(lows, highs, before);
            for (var i = 0; i < lows; i++) {
                var p = 2 * i;
                var left = high[highAt + high(mirror(p - 1, first, last), before)];
                var right = high[highAt + high(mirror(p + 1, first, last), before)];
                low[lowAt + i] -= (left + right + 2) >> 2;
            }
            for (var p = 1; p <= last; p += 2) {
                var left = low[lowAt + ((p - 1) >> 1)];
                var right = low[lowAt + (mirror(p + 1, first, last) >> 1)];
                high[highAt + high(p, before)] += (left + right) >> 1;
            }
            for (var p = 0; p < count; p++) {
                out[outAt + p] = (p & 1) == 0 ? low[lowAt + (p >> 1)] : high[highAt + high(p, before)];
            }
        }

        /**
         * Synthesizes a range of columns in place over rows: the low rows come first, then the high
         * rows.
         *
         * @param rows the samples, row by row
         * @param width the row length
         * @param start the first column
         * @param stop the column after the last
         * @param lows the low rows
         * @param highs the high rows
         * @param before whether the first high row precedes position 0
         */
        static void rows(int[] rows, int width, int start, int stop, int lows, int highs, boolean before) {
            var first = before ? -1 : 0;
            var last = last(lows, highs, before);
            for (var i = 0; i < lows; i++) {
                var p = 2 * i;
                var left = (lows + high(mirror(p - 1, first, last), before)) * width;
                var right = (lows + high(mirror(p + 1, first, last), before)) * width;
                var at = i * width;
                for (var x = start; x < stop; x++) {
                    rows[at + x] -= (rows[left + x] + rows[right + x] + 2) >> 2;
                }
            }
            for (var p = 1; p <= last; p += 2) {
                var left = ((p - 1) >> 1) * width;
                var right = (mirror(p + 1, first, last) >> 1) * width;
                var at = (lows + high(p, before)) * width;
                for (var x = start; x < stop; x++) {
                    rows[at + x] += (rows[left + x] + rows[right + x]) >> 1;
                }
            }
        }

        /**
         * Returns the row holding an output position after {@link #rows}.
         *
         * @param position the output position
         * @param lows the low rows
         * @param before whether the first high row precedes position 0
         * @return the row
         */
        static int rowOf(int position, int lows, boolean before) {
            return (position & 1) == 0 ? position >> 1 : lows + high(position, before);
        }
    }

    /**
     * The coded geometry of a tile along one axis: how many low and high coefficients each level
     * holds, the tile's own extent at each level, and whether one high coefficient precedes it.
     */
    private static final class Axis {
        /**
         * The low coefficients of each level, from index 1.
         */
        final int[] lows;

        /**
         * The high coefficients of each level, from index 1.
         */
        final int[] highs;

        /**
         * The tile's own extent at each level, index 0 being its photosites.
         */
        final int[] own;

        /**
         * Whether the tile has a neighbor before it, whose last high coefficient of each level it
         * carries first.
         */
        final boolean before;

        /**
         * Computes the geometry.
         *
         * @param extent the tile's extent in photosites of one plane
         * @param levels the number of levels
         * @param before whether the tile has a neighbor before it
         * @param after whether the tile has a neighbor after it
         */
        Axis(int extent, int levels, boolean before, boolean after) {
            lows = new int[levels + 1];
            highs = new int[levels + 1];
            own = new int[levels + 1];
            this.before = before;
            own[0] = extent;
            var reach = extent;
            for (var level = 1; level <= levels; level++) {
                var input = own[level - 1];
                var ownLows = (input + 1) / 2;
                own[level] = ownLows;
                if (after) {
                    // the coefficients the synthesis of the tile's reach needs, and one low more where the next
                    // level's own extent is odd
                    var needed = (reach & 1) == 0 ? reach / 2 + 1 : (reach + 1) / 2;
                    lows[level] = needed + ((ownLows & 1) == 1 && level < levels ? 1 : 0);
                    highs[level] = needed;
                    reach = lows[level];
                } else {
                    lows[level] = ownLows;
                    highs[level] = input / 2;
                }
                if (before) {
                    highs[level]++;
                }
            }
        }
    }

    /**
     * The adaptive Golomb-Rice decoder of one bitstream, row by row.
     */
    private static final class Coder {
        /**
         * The bitstream.
         */
        private final Bits bits;

        /**
         * The row length.
         */
        private final int width;

        /**
         * The coder kind: {@link #LOW}, {@link #HIGH} or {@link #MAP}.
         */
        private final int kind;

        /**
         * The value of the row above the first.
         */
        private final int first;

        /**
         * The Golomb-Rice parameter after each sample of the previous row (high bands only).
         */
        private final int[] parameters;

        /**
         * The Golomb-Rice parameter after each sample of the current row (high bands only).
         */
        private final int[] current;

        /**
         * The row above the first.
         */
        private final int[] top;

        /**
         * The Golomb-Rice parameter.
         */
        private int k;

        /**
         * The run index.
         */
        private int run;

        /**
         * Constructs the coder.
         *
         * @param bits the bitstream
         * @param width the row length
         * @param kind the coder kind
         * @param first the value of the row above the first, and for a map the first value
         */
        Coder(Bits bits, int width, int kind, int first) {
            this.bits = bits;
            this.width = width;
            this.kind = kind;
            this.first = first;
            this.k = kind == MAP ? 2 : 0;
            this.parameters = kind == HIGH ? new int[width] : null;
            this.current = kind == HIGH ? new int[width] : null;
            this.top = new int[width];
            Arrays.fill(top, kind == HIGH ? 0 : first);
        }

        /**
         * Decodes one row.
         *
         * @param values the array holding the row and the previous row
         * @param at the first sample of the row
         * @param above the first sample of the previous row, or -1 for the first row
         * @param y the row index
         */
        void row(int[] values, int at, int above, int y) {
            var prev = above < 0 ? top : values;
            var up = above < 0 ? 0 : above;
            var high = kind == HIGH;
            if (high) {
                Arrays.fill(current, 0);
            }
            var x = 0;
            if (kind == MAP && y == 0) {
                values[at] = first;
                x = 1;
            }
            while (x < width) {
                var b = prev[up + x];
                var a = x > 0 ? values[at + x - 1] : b;
                var c = x > 0 ? prev[up + x - 1] : b;
                var hasD = x + 1 < width;
                var d = hasD ? prev[up + x + 1] : 0;
                if (kind != MAP && hasD && a == b && b == d && (!high || a == 0)) {
                    var ran = false;
                    if (bits.bit() == 1) {
                        ran = true;
                        values[at + x++] = a;
                        while (x < width) {
                            if (bits.bit() == 1) {
                                var n = 1 << RUN_BITS[run];
                                if (x + n <= width) {
                                    run = Math.min(run + 1, RUN_BITS.length - 1);
                                }
                                n = Math.min(n, width - x);
                                Arrays.fill(values, at + x, at + x + n, a);
                                x += n;
                            } else {
                                var n = bits.read(RUN_BITS[run]);
                                if (n > width - x) {
                                    throw new MediaException.Malformed("CRX run past the end of a row");
                                }
                                Arrays.fill(values, at + x, at + x + n, a);
                                x += n;
                                break;
                            }
                        }
                        if (x >= width) {
                            break;
                        }
                    }
                    b = prev[up + x];
                    hasD = x + 1 < width;
                    d = hasD ? prev[up + x + 1] : 0;
                    var s = symbol();
                    if (high) {
                        // the interruption differs from the run's zero, so its symbols skip zero
                        values[at + x] = unsigned(s + 1);
                        adapt(s, x, y, hasD);
                    } else {
                        values[at + x] = b + unsigned(s);
                        k = update(k, !hasD || y == 0 ? s : (s + 2 * Math.abs(d - b)) >> 1);
                    }
                    if (ran) {
                        run = Math.max(run - 1, 0);
                    }
                    x++;
                    continue;
                }
                var s = symbol();
                if (high) {
                    values[at + x] = unsigned(s);
                    adapt(s, x, y, hasD);
                } else {
                    values[at + x] = median(a, b, c) + unsigned(s);
                    k = update(k, !hasD || y == 0 ? s : (s + 2 * Math.abs(d - b)) >> 1);
                }
                x++;
            }
            if (high) {
                System.arraycopy(current, 0, parameters, 0, width);
            }
        }

        /**
         * Updates the parameter of a high band after a symbol, raising it toward the parameter the
         * previous row had after the sample above the next one.
         *
         * @param s the symbol
         * @param x the sample's column
         * @param y the sample's row
         * @param hasD whether the sample has a right neighbor
         */
        private void adapt(int s, int x, int y, boolean hasD) {
            k = update(k, s);
            if (y > 0 && hasD && parameters[x + 1] - k > 1) {
                k++;
            }
            current[x] = k;
        }

        /**
         * Reads one Golomb-Rice symbol.
         *
         * @return the symbol
         */
        private int symbol() {
            var zeros = bits.zeros();
            if (zeros >= ESCAPE_ZEROS) {
                return bits.read(ESCAPE_BITS);
            }
            return (zeros << k) | bits.read(k);
        }

        /**
         * Returns the parameter after a symbol.
         *
         * @param k the parameter
         * @param estimate the estimate of the next symbol
         * @return the parameter
         */
        private static int update(int k, int estimate) {
            var q = estimate >> k;
            var next = k + (q >= 3 ? 1 : 0) + (q >= 6 ? 1 : 0);
            if (q == 0 && k > 0 && estimate < (1 << (k - 1))) {
                next--;
            }
            return Math.min(next, MAX_K);
        }

        /**
         * Maps a symbol to a signed value: 0, -1, 1, -2, 2 and so on.
         *
         * @param s the symbol
         * @return the value
         */
        private static int unsigned(int s) {
            return (s & 1) != 0 ? -((s + 1) >> 1) : s >> 1;
        }

        /**
         * Returns the median edge prediction.
         *
         * @param a the left neighbor
         * @param b the upper neighbor
         * @param c the upper-left neighbor
         * @return the prediction
         */
        private static int median(int a, int b, int c) {
            if (c >= Math.max(a, b)) {
                return Math.min(a, b);
            }
            if (c <= Math.min(a, b)) {
                return Math.max(a, b);
            }
            return a + b - c;
        }
    }

    /**
     * Reads the bits of one bitstream, most significant first; reading past its end yields zeros
     * and is reported by {@link #check()}.
     */
    private static final class Bits {
        /**
         * The data.
         */
        private final byte[] data;

        /**
         * The end of the bitstream.
         */
        private final int end;

        /**
         * The next byte to load.
         */
        private int position;

        /**
         * The loaded bits, the most recent in the low bits.
         */
        private long buffer;

        /**
         * The number of loaded bits not yet read.
         */
        private int count;

        /**
         * Constructs the reader.
         *
         * @param data the bytes
         * @param start the start of the bitstream
         * @param end the end of the bitstream
         */
        Bits(byte[] data, int start, int end) {
            this.data = data;
            this.position = start;
            this.end = end;
        }

        /**
         * Loads bytes until at least 57 bits are loaded.
         */
        private void fill() {
            while (count <= 56) {
                buffer = (buffer << 8) | (position < end ? data[position] & 0xFF : 0);
                position++;
                count += 8;
            }
        }

        /**
         * Reads one bit.
         *
         * @return the bit
         */
        int bit() {
            if (count == 0) {
                fill();
            }
            count--;
            return (int) (buffer >>> count) & 1;
        }

        /**
         * Reads bits.
         *
         * @param n the number of bits, from 0 to 32
         * @return the bits
         */
        int read(int n) {
            if (n == 0) {
                return 0;
            }
            if (n > 32) {
                throw new IllegalArgumentException();
            }
            if (count < n) {
                fill();
            }
            count -= n;
            return (int) ((buffer >>> count) & ((1L << n) - 1));
        }

        /**
         * Reads the zeros before the next one bit and the one bit, returning the zeros; a run longer
         * than an escape is cut short at the escape length plus one.
         *
         * @return the zeros
         * @throws MediaException.Malformed if the run of zeros reaches the end of the data
         */
        int zeros() {
            var zeros = 0;
            while (true) {
                if (count == 0) {
                    fill();
                }
                var window = buffer << (64 - count);
                var run = Long.numberOfLeadingZeros(window);
                if (run < count) {
                    zeros += run;
                    count -= run + 1;
                    return zeros;
                }
                zeros += count;
                count = 0;
                if (zeros > 2 * ESCAPE_ZEROS + 64 || position > end + 8) {
                    throw new MediaException.Malformed("CRX bitstream ends in a run of zeros");
                }
            }
        }

        /**
         * Checks that the decoding stayed within the bitstream.
         *
         * @throws MediaException.Malformed if it read past the end
         */
        void check() {
            if ((long) position * 8 - count > (long) end * 8) {
                throw new MediaException.Malformed("CRX bitstream ends early");
            }
        }
    }

    /**
     * Reads a big-endian 16-bit value.
     *
     * @param data the bytes
     * @param at the offset
     * @return the value
     */
    private static int u16(byte[] data, int at) {
        return ((data[at] & 0xFF) << 8) | (data[at + 1] & 0xFF);
    }

    /**
     * Reads a big-endian 32-bit value.
     *
     * @param data the bytes
     * @param at the offset
     * @return the value
     */
    private static int u32(byte[] data, int at) {
        return ((data[at] & 0xFF) << 24) | ((data[at + 1] & 0xFF) << 16) | ((data[at + 2] & 0xFF) << 8)
                | (data[at + 3] & 0xFF);
    }
}
