/* Ports X3F tools (x3f_process.c: interpolate_bad_pixels; x3f_spatial_gain.c: lens_position, get_MOD,
   get_merrill_type_gains_table, x3f_get_merrill_type_spatial_gain, x3f_get_interp_merrill_type_spatial_gain,
   x3f_get_classic_spatial_gain, x3f_calc_spatial_gain) at commit e8b48ca. Copyright 2015 Roland and Erik Karlsson;
   BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.raw.internal;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads the sensor corrections of a Sigma X3F camera metadata block ({@link X3fCamf}): the bad
 * pixel lists, which are interpolated over in the stored samples, and the spatial gain tables of
 * the lens shading, which become a DNG {@code GainMap} opcode list applied to the linear data.
 *
 * <p>Bad pixels come from {@code BadPixels} (packed column and row relative to
 * {@code KeepImageArea}), {@code BadPixelsF20} and {@code Jpeg_BadClusters} (row, column and a
 * third value per entry), the grid of {@code HighlightPixelsInfo}, the run lists
 * {@code BadPixelsChromaF23} and, for a Quattro top layer, {@code BadPixelsLumaF23} (a row, then
 * pairs of a column and a second value, ended by a 0), and the autofocus photosites of the sd
 * Quattro and sd Quattro H. They are fixed in passes: a pixel whose four neighbors are good takes
 * their rounded mean, else one whose left and right or upper and lower neighbors are, and once a
 * pass fixes none, also one with two good neighbors at a corner; a pixel no pass can fix keeps its
 * value.
 *
 * <p>The spatial gain is interpolated, for the TRUE engine's Merrill and Quattro cameras, between
 * the tables of the {@code SpatialGainsProps} blocks closest to the shot's aperture and lens
 * position (the focus position of the object distance), one table per channel, each element
 * {@code MinGains + Delta * GainsTable}; older cameras give one table of three channels for the
 * white balance setting ({@code SpatialGainTables}) or for all of them ({@code SpatialGain}). A
 * table spans the whole decoded image, its first and last rows and columns at the image's first
 * row and column and one past its last.
 *
 * @implNote This implementation ports the corrections of the X3F tools library, whose license was
 * checked; it applies them to the stored samples and the linear data rather than to that library's
 * rescaled intermediate values, which changes the result only by rounding.
 */
final class X3fCorrections {
    /**
     * The {@code CAMERAID} of the sd Quattro.
     */
    static final int SD_QUATTRO = 40;

    /**
     * The {@code CAMERAID} of the sd Quattro H.
     */
    static final int SD_QUATTRO_H = 41;

    /**
     * The autofocus photosite grids, as first, last, pitch and size of the columns, then of the
     * rows: the sd Quattro's top and lower layers, then the sd Quattro H's.
     */
    private static final int[][] AUTOFOCUS = {
            {217, 5641, 16, 1, 464, 3312, 32, 2}, {108, 2820, 8, 1, 232, 1656, 16, 1},
            {233, 6425, 16, 1, 592, 3888, 32, 2}, {116, 2820, 8, 1, 296, 1944, 16, 1}};

    /**
     * The name of a Merrill block of an aperture index and a focus distance.
     */
    private static final Pattern INDEXED_BLOCK = Pattern.compile("SpatialGainsProps_(\\d+)_(\\S{1,3})");

    /**
     * The name of a Merrill block of an aperture and a lens position.
     */
    private static final Pattern VALUED_BLOCK = Pattern.compile(
            "SpatialGainsProps_([-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?)_([-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?)");

    /**
     * The leading number of a property value.
     */
    private static final Pattern LEADING_NUMBER = Pattern.compile(
            "\\s*([-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?).*", Pattern.DOTALL);

    /**
     * The opcode version written in the gain map opcodes.
     */
    private static final int OPCODE_VERSION = 0x01030000;

    /**
     * A spatial gain table: gains on a grid over the whole decoded image for a run of channels.
     *
     * @param rows the grid rows, at least 1
     * @param columns the grid columns, at least 1
     * @param channel the first channel the table applies to
     * @param channels the channels it applies to
     * @param gains the gains, row by row, the channels of each point together
     */
    record Gain(int rows, int columns, int channel, int channels, double[] gains) {
    }

    /**
     * A Merrill gain block and its place in the aperture and lens position plane.
     *
     * @param name the block name
     * @param x the reciprocal of the block's aperture
     * @param y the block's lens position in millimeters
     */
    private record Block(String name, double x, double y) {
    }

    /**
     * Prevents instantiation.
     */
    private X3fCorrections() {
        throw new AssertionError();
    }

    /**
     * Returns the camera identifier of a metadata block.
     *
     * @param camf the metadata block, or {@code null}
     * @return the {@code CAMERAID}, or -1 when the block has none
     */
    static long cameraId(X3fCamf camf) {
        var values = camf != null ? unsigned(camf, "CAMERAID", 1) : null;
        return values != null ? (long) values[0] : -1;
    }

    /**
     * Interpolates over the bad pixels a metadata block lists for an image.
     *
     * @param camf the metadata block
     * @param samples the image, interleaved, the first {@code colors} samples of each pixel fixed
     * @param width the image width
     * @param height the image height
     * @param channels the samples of each pixel
     * @param colors 3 for the image of every layer (of the lower layers of a Quattro file), 1 for a
     * Quattro top layer
     */
    static void fixBadPixels(X3fCamf camf, short[] samples, int width, int height, int channels, int colors) {
        var bad = new BadPixels(width, height);
        if (colors == 3) {
            var keep = unsigned(camf, "KeepImageArea", 4);
            var list = vector(camf, "BadPixels");
            if (keep != null && list != null) {
                for (var value : list) {
                    var packed = (long) value;
                    bad.mark(((packed & 0x000FFF00L) >>> 8) - (long) keep[0],
                            ((packed & 0xFFF00000L) >>> 20) - (long) keep[1]);
                }
            }
            for (var name : new String[]{"BadPixelsF20", "Jpeg_BadClusters"}) {
                var dimensions = camf.dimensions(name);
                var entries = camf.numbers(name, X3fCamf.Element.UNSIGNED);
                if (dimensions != null && dimensions.length == 2 && dimensions[0] == 3 && entries != null) {
                    for (var row = 0; row < dimensions[1]; row++) {
                        bad.mark((long) entries[3 * row + 1], (long) entries[3 * row]);
                    }
                }
            }
            var highlights = unsigned(camf, "HighlightPixelsInfo", 2, 2);
            if (highlights != null && highlights[2] > 0 && highlights[3] > 0) {
                for (var row = (long) highlights[1]; row < height; row += (long) highlights[3]) {
                    for (var column = (long) highlights[0]; column < width; column += (long) highlights[2]) {
                        bad.mark(column, row);
                    }
                }
            }
        }
        var runs = vector(camf, colors == 1 ? "BadPixelsLumaF23" : "BadPixelsChromaF23");
        if (runs != null) {
            var row = -1L;
            for (var index = 0; index < runs.length; index++) {
                var value = (long) runs[index];
                if (row == -1) {
                    row = value;
                } else if (value == 0) {
                    row = -1;
                } else {
                    bad.mark(value, row);
                    index++;
                }
            }
        }
        var camera = cameraId(camf);
        if (camera == SD_QUATTRO || camera == SD_QUATTRO_H) {
            var grid = AUTOFOCUS[(camera == SD_QUATTRO_H ? 2 : 0) + (colors == 1 ? 0 : 1)];
            for (var row = grid[4]; row <= grid[5]; row += grid[6]) {
                for (var column = grid[0]; column <= grid[1]; column += grid[2]) {
                    for (var r = 0; r < grid[7]; r++) {
                        for (var c = 0; c < grid[3]; c++) {
                            bad.mark(column + c, row + r);
                        }
                    }
                }
            }
        }
        bad.interpolate(samples, channels, colors);
    }

    /**
     * Holds the bad pixels of an image: a list in marking order and a bit per pixel.
     */
    private static final class BadPixels {
        /**
         * The image width.
         */
        private final int width;

        /**
         * The image height.
         */
        private final int height;

        /**
         * One bit per pixel, set for a pixel still to fix.
         */
        private final long[] marks;

        /**
         * The pixel indices still to fix, in the first {@link #count} elements.
         */
        private int[] pending = new int[64];

        /**
         * The number of pixels still to fix.
         */
        private int count;

        /**
         * Constructs an empty set.
         *
         * @param width the image width
         * @param height the image height
         */
        BadPixels(int width, int height) {
            this.width = width;
            this.height = height;
            this.marks = new long[(int) (((long) width * height + 63) >>> 6)];
        }

        /**
         * Marks a pixel; one outside the image or marked already is ignored.
         *
         * @param column the column, any value
         * @param row the row, any value
         */
        void mark(long column, long row) {
            if (column < 0 || column >= width || row < 0 || row >= height) {
                return;
            }
            var index = (int) (row * width + column);
            if (marked(index)) {
                return;
            }
            marks[index >>> 6] |= 1L << index;
            if (count == pending.length) {
                pending = Arrays.copyOf(pending, count * 2);
            }
            pending[count++] = index;
        }

        /**
         * Returns whether a pixel is marked.
         *
         * @param index the pixel index
         * @return whether it is still to fix
         */
        private boolean marked(int index) {
            return (marks[index >>> 6] & (1L << index)) != 0;
        }

        /**
         * Returns whether a neighbor can be read: inside the image and not marked.
         *
         * @param column the neighbor's column
         * @param row the neighbor's row
         * @return whether it is good
         */
        private boolean good(int column, int row) {
            return column >= 0 && column < width && row >= 0 && row < height && !marked(row * width + column);
        }

        /**
         * Fixes the marked pixels in passes.
         *
         * @param samples the image
         * @param channels the samples of each pixel
         * @param colors the samples fixed
         */
        void interpolate(short[] samples, int channels, int colors) {
            var corners = false;
            var fixed = new int[Math.max(1, count)];
            var neighbors = new int[4];
            while (count > 0) {
                var fixedCount = 0;
                var kept = 0;
                for (var at = 0; at < count; at++) {
                    var index = pending[at];
                    var column = index % width;
                    var row = index / width;
                    var left = good(column - 1, row);
                    var right = good(column + 1, row);
                    var up = good(column, row - 1);
                    var down = good(column, row + 1);
                    var number = 0;
                    if (left && right && up && down) {
                        neighbors[number++] = index - 1;
                        neighbors[number++] = index + 1;
                        neighbors[number++] = index - width;
                        neighbors[number++] = index + width;
                    } else if (left && right) {
                        neighbors[number++] = index - 1;
                        neighbors[number++] = index + 1;
                    } else if (up && down) {
                        neighbors[number++] = index - width;
                        neighbors[number++] = index + width;
                    } else if (corners && (left ? 1 : 0) + (right ? 1 : 0) + (up ? 1 : 0) + (down ? 1 : 0) == 2) {
                        if (left) {
                            neighbors[number++] = index - 1;
                        }
                        if (right) {
                            neighbors[number++] = index + 1;
                        }
                        if (up) {
                            neighbors[number++] = index - width;
                        }
                        if (down) {
                            neighbors[number++] = index + width;
                        }
                    } else {
                        pending[kept++] = index;
                        continue;
                    }
                    for (var color = 0; color < colors; color++) {
                        var sum = 0;
                        for (var neighbor = 0; neighbor < number; neighbor++) {
                            sum += samples[neighbors[neighbor] * channels + color] & 0xFFFF;
                        }
                        samples[index * channels + color] = (short) ((sum + number / 2) / number);
                    }
                    fixed[fixedCount++] = index;
                }
                count = kept;
                if (fixedCount == 0) {
                    if (corners) {
                        return;
                    }
                    corners = true;
                }
                for (var at = 0; at < fixedCount; at++) {
                    marks[fixed[at] >>> 6] &= ~(1L << fixed[at]);
                }
            }
        }
    }

    /**
     * Returns the spatial gain tables of a shot.
     *
     * @param camf the metadata block
     * @param balance the name of the as-shot white balance setting
     * @param focalLength the focal length in millimeters
     * @return the tables, empty when the block has none
     */
    static List<Gain> spatialGain(X3fCamf camf, String balance, double focalLength) {
        // TODO: apply the sd Quattro H's high-resolution mode tables (SpatialGainHP) once a CC0 sample of that mode
        // shows how they combine with these
        var merrill = merrillGain(camf, focalLength);
        if (merrill != null) {
            return merrill;
        }
        var name = camf.property("SpatialGainTables", balance);
        var table = name != null ? classicGain(camf, name) : null;
        if (table == null) {
            table = classicGain(camf, "SpatialGain");
        }
        return table != null ? List.of(table) : List.of();
    }

    /**
     * Returns an older camera's table of three-dimensional floating-point gains.
     *
     * @param camf the metadata block
     * @param name the matrix name
     * @return the table, or {@code null} when the matrix is missing or of another shape
     */
    private static Gain classicGain(X3fCamf camf, String name) {
        var dimensions = camf.dimensions(name);
        var gains = camf.numbers(name, X3fCamf.Element.FLOAT);
        if (dimensions == null || dimensions.length != 3 || gains == null || dimensions[0] < 1 || dimensions[1] < 1
                || dimensions[2] < 1) {
            return null;
        }
        return new Gain(dimensions[0], dimensions[1], 0, dimensions[2], gains);
    }

    /**
     * Returns the interpolated tables of a Merrill or Quattro camera, one per channel.
     *
     * @param camf the metadata block
     * @param focalLength the focal length in millimeters
     * @return the tables, or {@code null} when the block has no complete set
     */
    private static List<Gain> merrillGain(X3fCamf camf, double focalLength) {
        var aperture = floats(camf, "CaptureAperture", 1);
        var blockNames = camf.properties("IncludeBlocks");
        if (aperture == null || !camf.isPropertyList("IncludeBlocks")) {
            return null;
        }
        var blocks = new ArrayList<Block>();
        var stops = vectorOf(camf, "SpatialGain_Fstop", X3fCamf.Element.FLOAT);
        for (var property : blockNames) {
            var name = property[0];
            if (!camf.isPropertyList(name)) {
                continue;
            }
            if (stops != null) {
                var matcher = INDEXED_BLOCK.matcher(name);
                if (!matcher.matches()) {
                    continue;
                }
                long index;
                try {
                    index = Long.parseLong(matcher.group(1));
                } catch (NumberFormatException exception) {
                    continue;
                }
                if (index >= stops.length) {
                    continue;
                }
                double position;
                if (matcher.group(2).equals("INF")) {
                    position = lensPosition(focalLength, Double.POSITIVE_INFINITY);
                } else if (matcher.group(2).equals("MOD")) {
                    position = lensPosition(focalLength, minimumObjectDistance(camf));
                } else {
                    continue;
                }
                blocks.addFirst(new Block(name, 1 / stops[(int) index], position));
            } else {
                var matcher = VALUED_BLOCK.matcher(name);
                if (matcher.matches()) {
                    blocks.addFirst(new Block(name, 1 / Double.parseDouble(matcher.group(1)),
                            Double.parseDouble(matcher.group(2))));
                }
            }
        }
        var x = 1 / aperture[0];
        var distance = floats(camf, "ObjectDistance", 1);
        var y = lensPosition(focalLength, distance != null ? distance[0] * 10 : Double.POSITIVE_INFINITY);
        var closest = new Block[4];
        var dxs = new double[]{Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY};
        var dys = new double[]{Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.NEGATIVE_INFINITY};
        var distances = new double[]{Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY};
        for (var block : blocks) {
            var dx = block.x - x;
            var dy = block.y - y;
            var d2 = dx * dx + dy * dy;
            int quadrant;
            if (dx > 0 && dy > 0) {
                quadrant = 0;
            } else if (dx > 0) {
                quadrant = 3;
            } else if (dy > 0) {
                quadrant = 1;
            } else {
                quadrant = 2;
            }
            if (d2 < distances[quadrant]) {
                closest[quadrant] = block;
                dxs[quadrant] = dx;
                dys[quadrant] = dy;
                distances[quadrant] = d2;
            }
        }
        var weightX = new double[]{dxs[1] / (dxs[1] - dxs[0]), dxs[0] / (dxs[0] - dxs[1]),
                dxs[3] / (dxs[3] - dxs[2]), dxs[2] / (dxs[2] - dxs[3])};
        var weightY = new double[]{dys[3] / (dys[3] - dys[0]), dys[2] / (dys[2] - dys[1]),
                dys[1] / (dys[1] - dys[2]), dys[0] / (dys[0] - dys[3])};
        var weights = new double[4];
        for (var quadrant = 0; quadrant < 4; quadrant++) {
            weights[quadrant] = (Double.isNaN(weightX[quadrant]) ? 1 : weightX[quadrant])
                    * (Double.isNaN(weightY[quadrant]) ? 1 : weightY[quadrant]);
        }
        var channels = new String[]{"R", "G", "B"};
        var tables = new ArrayList<Gain>();
        for (var channel = 0; channel < 3; channel++) {
            var rows = -1;
            var columns = -1;
            double[] gains = null;
            for (var quadrant = 0; quadrant < 4; quadrant++) {
                if (closest[quadrant] == null) {
                    continue;
                }
                var block = closest[quadrant].name;
                var tableName = camf.property(block, "GainsTable" + channels[channel]);
                var minimum = number(camf.property(block, "MinGains" + channels[channel]));
                var delta = number(camf.property(block, "Delta" + channels[channel]));
                var dimensions = tableName != null ? camf.dimensions(tableName) : null;
                var table = tableName != null ? camf.numbers(tableName, X3fCamf.Element.UNSIGNED) : null;
                if (dimensions == null || dimensions.length != 2 || table == null || (rows != -1 && rows != dimensions[0])
                        || (columns != -1 && columns != dimensions[1]) || minimum == null || delta == null) {
                    return null;
                }
                rows = dimensions[0];
                columns = dimensions[1];
                if (gains == null) {
                    gains = new double[table.length];
                }
                for (var index = 0; index < gains.length; index++) {
                    gains[index] += weights[quadrant] * (minimum + delta * table[index]);
                }
            }
            if (gains == null || rows < 1 || columns < 1) {
                return null;
            }
            tables.add(new Gain(rows, columns, channel, 1, gains));
        }
        return tables;
    }

    /**
     * Returns the position of the lens focused at a distance, from the thin lens equation.
     *
     * @param focalLength the focal length in millimeters
     * @param distance the object distance in millimeters, infinite for focus at infinity
     * @return the distance from the lens to the image in millimeters
     */
    private static double lensPosition(double focalLength, double distance) {
        return 1 / (1 / focalLength - 1 / distance);
    }

    /**
     * Returns the minimum object distance of the camera's lens.
     *
     * @param camf the metadata block
     * @return the distance in millimeters: from the lens identifier of the DP1, DP2 and DP3
     * Merrill, 280 otherwise
     */
    private static double minimumObjectDistance(X3fCamf camf) {
        var lens = matrixOf(camf, "LensInformation", X3fCamf.Element.SIGNED, 1);
        return switch (lens != null ? (int) lens[0] : -1) {
            case 1003 -> 200;
            case 1005 -> 226;
            default -> 280;
        };
    }

    /**
     * Parses the number a property value starts with, as C's {@code atof} does.
     *
     * @param value the value, or {@code null}
     * @return the number, 0 when the value starts with none, or {@code null} for a missing value
     */
    private static Double number(String value) {
        if (value == null) {
            return null;
        }
        var matcher = LEADING_NUMBER.matcher(value);
        return matcher.matches() ? Double.parseDouble(matcher.group(1)) : 0.0;
    }

    /**
     * Returns a one-dimensional unsigned matrix of any length.
     *
     * @param camf the metadata block
     * @param name the matrix name
     * @return the elements, or {@code null}
     */
    private static double[] vector(X3fCamf camf, String name) {
        return vectorOf(camf, name, X3fCamf.Element.UNSIGNED);
    }

    /**
     * Returns a one-dimensional matrix of any length whose elements are of a kind.
     *
     * @param camf the metadata block
     * @param name the matrix name
     * @param element the kind of the elements
     * @return the elements, or {@code null}
     */
    private static double[] vectorOf(X3fCamf camf, String name, X3fCamf.Element element) {
        var dimensions = camf.dimensions(name);
        return dimensions != null && dimensions.length == 1 ? camf.numbers(name, element) : null;
    }

    /**
     * Returns an unsigned matrix of exact dimensions.
     *
     * @param camf the metadata block
     * @param name the matrix name
     * @param sizes the size of each dimension
     * @return the elements, or {@code null}
     */
    static double[] unsigned(X3fCamf camf, String name, int... sizes) {
        return matrixOf(camf, name, X3fCamf.Element.UNSIGNED, sizes);
    }

    /**
     * Returns a floating-point matrix of exact dimensions.
     *
     * @param camf the metadata block
     * @param name the matrix name
     * @param sizes the size of each dimension
     * @return the elements, or {@code null}
     */
    private static double[] floats(X3fCamf camf, String name, int... sizes) {
        return matrixOf(camf, name, X3fCamf.Element.FLOAT, sizes);
    }

    /**
     * Returns a matrix of exact dimensions whose elements are of a kind.
     *
     * @param camf the metadata block
     * @param name the matrix name
     * @param element the kind of the elements
     * @param sizes the size of each dimension
     * @return the elements, or {@code null}
     */
    private static double[] matrixOf(X3fCamf camf, String name, X3fCamf.Element element, int... sizes) {
        var dimensions = camf.dimensions(name);
        return Arrays.equals(dimensions, sizes) ? camf.numbers(name, element) : null;
    }

    /**
     * Writes the spatial gain tables as a DNG opcode list of {@code GainMap} opcodes over the active
     * area.
     *
     * @param gains the tables
     * @param width the width of the decoded image the tables span
     * @param height the height of the decoded image
     * @param active the active area, top, left, bottom and right, the last two exclusive
     * @return the opcode list, or {@code null} for no tables
     */
    static byte[] gainMap(List<Gain> gains, int width, int height, int[] active) {
        if (gains.isEmpty()) {
            return null;
        }
        var activeHeight = active[2] - active[0];
        var activeWidth = active[3] - active[1];
        var out = new ByteArrayOutputStream();
        out.writeBytes(ByteBuffer.allocate(4).putInt(gains.size()).array());
        for (var gain : gains) {
            var parameters = ByteBuffer.allocate(76 + 4 * gain.gains.length);
            parameters.putInt(0).putInt(0).putInt(activeHeight).putInt(activeWidth).putInt(gain.channel)
                    .putInt(gain.channels).putInt(1).putInt(1).putInt(gain.rows).putInt(gain.columns);
            parameters.putDouble(gain.rows > 1 ? (double) height / ((gain.rows - 1) * (double) activeHeight) : 1);
            parameters.putDouble(gain.columns > 1 ? (double) width / ((gain.columns - 1) * (double) activeWidth) : 1);
            parameters.putDouble(-(double) active[0] / activeHeight);
            parameters.putDouble(-(double) active[1] / activeWidth);
            parameters.putInt(gain.channels);
            for (var value : gain.gains) {
                parameters.putFloat((float) value);
            }
            out.writeBytes(ByteBuffer.allocate(16).putInt(9).putInt(OPCODE_VERSION).putInt(0)
                    .putInt(parameters.capacity()).array());
            out.writeBytes(parameters.array());
        }
        return out.toByteArray();
    }
}
