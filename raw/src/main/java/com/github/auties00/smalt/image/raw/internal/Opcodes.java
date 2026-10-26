package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.ByteArrays;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies DNG opcode lists (DNG specification 1.7.1, chapter 7): {@code WarpRectilinear} (1),
 * {@code WarpFisheye} (2), {@code FixVignetteRadial} (3), {@code FixBadPixelsConstant} (4),
 * {@code FixBadPixelsList} (5), {@code TrimBounds} (6), {@code MapTable} (7),
 * {@code MapPolynomial} (8), {@code GainMap} (9), {@code DeltaPerRow} (10), {@code DeltaPerColumn}
 * (11), {@code ScalePerRow} (12), {@code ScalePerColumn} (13) and {@code WarpRectilinear2} (14).
 *
 * <p>Opcode list 1 applies to the stored samples, list 2 to the linear image of the active area
 * and list 3 to the demosaiced image; values are clipped to the list's logical range after each
 * opcode that changes them (0 to 65535 for list 1, 0 to 1 otherwise). Opcodes flagged for preview
 * quality processing only are applied too, since the decoder renders at full quality.
 * {@code TrimBounds} restricts the opcodes after it to the trimmed rectangle, in that rectangle's
 * coordinates, and {@link #bounds} gives the rectangle the output keeps; a {@code WarpRectilinear}
 * or {@code WarpFisheye} right after an optional {@code WarpRectilinear2} is skipped, as the
 * specification asks.
 *
 * @implNote This implementation skips opcodes of later specifications whether or not they are
 * flagged optional, so a file that needs one still renders, uncorrected. Gain map
 * positions are the pixel coordinates divided by the image size, and warps resample with a
 * Catmull-Rom cubic.
 */
final class Opcodes {
    /**
     * The largest opcode list processed, in opcodes.
     */
    private static final int MAX_OPCODES = 4096;

    /**
     * Prevents instantiation.
     */
    private Opcodes() {
        throw new AssertionError();
    }

    /**
     * Reads and writes the samples of an image an opcode list applies to.
     */
    private interface Surface {
        /**
         * {@return the width in pixels}
         */
        int width();

        /**
         * {@return the height in pixels}
         */
        int height();

        /**
         * Returns the number of planes.
         *
         * @return the planes
         */
        int planes();

        /**
         * Returns a sample.
         *
         * @param row the row, from 0
         * @param column the column, from 0
         * @param plane the plane index
         * @return the value
         */
        float get(int row, int column, int plane);

        /**
         * Stores a sample, clipped to the logical range.
         *
         * @param row the row, from 0
         * @param column the column, from 0
         * @param plane the plane index
         * @param value the sample in the logical range of 0 to 1
         */
        void set(int row, int column, int plane, float value);

        /**
         * Returns the largest logical value.
         *
         * @return 65535 for stored samples, 1 otherwise
         */
        float maximum();
    }

    /**
     * Applies an opcode list to the stored samples of a sensor.
     *
     * @param sensor the sensor description
     * @param plane the samples
     * @param list the opcode list
     */
    static void applyToSensor(Sensor sensor, SensorPlane plane, byte[] list) {
        apply(new Surface() {
            @Override
            public int width() {
                return plane.width;
            }

            @Override
            public int height() {
                return plane.height;
            }

            @Override
            public int planes() {
                return plane.samplesPerPixel;
            }

            @Override
            public float get(int row, int column, int p) {
                return plane.sample((row * plane.width + column) * plane.samplesPerPixel + p);
            }

            @Override
            public void set(int row, int column, int p, float value) {
                var index = (row * plane.width + column) * plane.samplesPerPixel + p;
                if (plane.integers != null) {
                    plane.integers[index] = (short) Math.clamp(Math.round(value), 0, 65535);
                } else if (plane.wide != null) {
                    plane.wide[index] = (int) Math.clamp(Math.round((double) value), 0L, 0xFFFFFFFFL);
                } else {
                    plane.floats[index] = value;
                }
            }

            @Override
            public float maximum() {
                return plane.integers != null ? 65535 : plane.wide != null ? 4294967295f : Float.MAX_VALUE;
            }
        }, list);
    }

    /**
     * Applies an opcode list to a linear image.
     *
     * @param image the linear image, modified in place
     * @param list the opcode list
     * @param start the rectangle earlier lists trimmed the image to, as left, top, right and
     * bottom, or {@code null}
     */
    static void applyToLinear(LinearImage image, byte[] list, int[] start) {
        var spp = image.samplesPerPixel;
        apply(start(start, new Surface() {
            @Override
            public int width() {
                return image.width;
            }

            @Override
            public int height() {
                return image.height;
            }

            @Override
            public int planes() {
                return spp;
            }

            @Override
            public float get(int row, int column, int plane) {
                return image.data[(row * image.width + column) * spp + plane];
            }

            @Override
            public void set(int row, int column, int plane, float value) {
                image.data[(row * image.width + column) * spp + plane] = Math.clamp(value, 0f, 1f);
            }

            @Override
            public float maximum() {
                return 1;
            }
        }), list);
    }

    /**
     * Applies an opcode list to demosaiced planes.
     *
     * @param planes the red, green and blue planes
     * @param width the width of the planes in pixels
     * @param height the height of the planes in pixels
     * @param list the opcode list
     * @param start the rectangle earlier lists trimmed the image to, as left, top, right and
     * bottom, or {@code null}
     */
    static void applyToRgb(float[][] planes, int width, int height, byte[] list, int[] start) {
        apply(start(start, new Surface() {
            @Override
            public int width() {
                return width;
            }

            @Override
            public int height() {
                return height;
            }

            @Override
            public int planes() {
                return 3;
            }

            @Override
            public float get(int row, int column, int plane) {
                return planes[plane][row * width + column];
            }

            @Override
            public void set(int row, int column, int plane, float value) {
                planes[plane][row * width + column] = Math.clamp(value, 0f, 1f);
            }

            @Override
            public float maximum() {
                return 1;
            }
        }), list);
    }

    /**
     * Restricts a surface to the rectangle earlier opcode lists trimmed it to.
     *
     * @param bounds the left, top, right and bottom edges, or {@code null}
     * @param surface the surface of the whole image
     * @return the restricted surface
     */
    private static Surface start(int[] bounds, Surface surface) {
        return bounds == null ? surface : trimmed(surface, new int[]{bounds[1], bounds[0], bounds[3], bounds[2]});
    }

    /**
     * Reads the big-endian fields of an opcode list.
     */
    private static final class Reader {
        /**
         * The list.
         */
        private final byte[] data;

        /**
         * The next index.
         */
        private int position;

        /**
         * The index after the current opcode's parameters.
         */
        private int end;

        /**
         * Constructs a reader.
         *
         * @param data the list
         */
        Reader(byte[] data) {
            this.data = data;
            this.end = data.length;
        }

        /**
         * Reads an unsigned 32-bit integer.
         *
         * @return the value
         */
        long u32() {
            if (position + 4 > end) {
                throw new MediaException.Malformed("truncated DNG opcode");
            }
            var value = ByteArrays.u32be(data, position);
            position += 4;
            return value;
        }

        /**
         * Reads an unsigned 32-bit integer as a non-negative {@code int}.
         *
         * @return the value, clamped to the {@code int} range
         */
        int int32() {
            return (int) Math.min(u32(), Integer.MAX_VALUE);
        }

        /**
         * Reads an unsigned 16-bit integer.
         *
         * @return the value
         */
        int u16() {
            if (position + 2 > end) {
                throw new MediaException.Malformed("truncated DNG opcode");
            }
            var value = ByteArrays.u16be(data, position);
            position += 2;
            return value;
        }

        /**
         * Reads a 32-bit float.
         *
         * @return the value
         */
        float f32() {
            return Float.intBitsToFloat((int) u32());
        }

        /**
         * Reads a 64-bit double.
         *
         * @return the value
         */
        double f64() {
            var high = u32();
            var low = u32();
            return Double.longBitsToDouble((high << 32) | low);
        }
    }

    /**
     * An area of an image an opcode modifies.
     *
     * @param top the first row
     * @param left the first column
     * @param bottom the row after the last
     * @param right the column after the last
     * @param plane the first plane
     * @param planes the number of planes
     * @param rowPitch the distance between modified rows
     * @param colPitch the distance between modified columns
     */
    private record Area(int top, int left, int bottom, int right, int plane, int planes, int rowPitch, int colPitch) {
        /**
         * Reads an area and clips it to the surface.
         *
         * @param reader the parameters
         * @param surface the surface the opcode applies to
         * @return the area
         */
        static Area read(Reader reader, Surface surface) {
            var top = reader.int32();
            var left = reader.int32();
            var bottom = Math.min(reader.int32(), surface.height());
            var right = Math.min(reader.int32(), surface.width());
            var plane = reader.int32();
            var planes = reader.int32();
            var rowPitch = Math.max(1, reader.int32());
            var colPitch = Math.max(1, reader.int32());
            planes = (int) Math.min(planes, Math.max(0, (long) surface.planes() - plane));
            return new Area(top, left, Math.max(top, bottom), Math.max(left, right), plane, planes, rowPitch, colPitch);
        }
    }

    /**
     * Applies every opcode of a list.
     *
     * @param surface the image
     * @param list the opcode list
     */
    private static void apply(Surface surface, byte[] list) {
        var reader = new Reader(list);
        var count = reader.u32();
        if (count > MAX_OPCODES) {
            throw new MediaException.Malformed("DNG opcode list of " + count + " opcodes");
        }
        var skipWarp = false;
        var current = surface;
        for (var index = 0; index < count; index++) {
            reader.end = list.length;
            var id = reader.int32();
            reader.u32();
            var flags = reader.u32();
            var length = reader.u32();
            if (length > list.length - reader.position) {
                throw new MediaException.Malformed("DNG opcode parameters overrun the list");
            }
            var start = reader.position;
            reader.end = start + (int) length;
            var optional = (flags & 1) != 0;
            try {
                switch (id) {
                    case 1 -> {
                        if (!skipWarp) {
                            warpRectilinear(reader, current);
                        }
                    }
                    case 2 -> {
                        if (!skipWarp) {
                            warpFisheye(reader, current);
                        }
                    }
                    case 3 -> fixVignetteRadial(reader, current);
                    case 4 -> fixBadPixelsConstant(reader, current);
                    case 5 -> fixBadPixelsList(reader, current);
                    case 6 -> current = trimmed(current, readTrim(reader));
                    case 7 -> mapTable(reader, current);
                    case 8 -> mapPolynomial(reader, current);
                    case 9 -> gainMap(reader, current);
                    case 10, 11, 12, 13 -> perLine(reader, current, id);
                    case 14 -> warpRectilinear2(reader, current);
                    default -> {
                    }
                }
            } catch (MediaException.Malformed exception) {
                if (!optional) {
                    throw exception;
                }
            }
            skipWarp = id == 14 && optional;
            reader.position = start + (int) length;
        }
    }

    /**
     * Returns the rectangle the output keeps after the {@code TrimBounds} opcodes of a sensor's
     * opcode lists, in the coordinates of the active area: the rectangles of list 1, given in
     * stored coordinates, are moved by the active area's origin, and each rectangle is relative to
     * the image the earlier ones left.
     *
     * @param sensor the sensor description
     * @param width the width of the active area
     * @param height the height of the active area
     * @param lists the number of lists considered, from list 1: 1, 2 or 3
     * @return the left, top, right and bottom edges, the last two exclusive, or {@code null} when
     * no list trims the image
     * @throws MediaException.Malformed if a list is corrupt
     */
    static int[] bounds(Sensor sensor, int width, int height, int lists) {
        var active = sensor.active();
        int[] bounds = null;
        var all = new byte[][]{sensor.opcodes1, sensor.opcodes2, sensor.opcodes3};
        for (var index = 0; index < lists; index++) {
            var list = all[index];
            if (list == null) {
                continue;
            }
            for (var trim : trims(list)) {
                var shiftX = index == 0 && bounds == null ? -active[1] : 0;
                var shiftY = index == 0 && bounds == null ? -active[0] : 0;
                var baseX = bounds == null ? 0 : bounds[0];
                var baseY = bounds == null ? 0 : bounds[1];
                var limitX = bounds == null ? width : bounds[2];
                var limitY = bounds == null ? height : bounds[3];
                var left = Math.max(baseX, baseX + trim[1] + shiftX);
                var top = Math.max(baseY, baseY + trim[0] + shiftY);
                var right = Math.min(limitX, baseX + trim[3] + shiftX);
                var bottom = Math.min(limitY, baseY + trim[2] + shiftY);
                if (right > left && bottom > top) {
                    bounds = new int[]{left, top, right, bottom};
                }
            }
        }
        return bounds;
    }

    /**
     * Returns the rectangles of the {@code TrimBounds} opcodes of a list, in list order.
     *
     * @param list the opcode list
     * @return the top, left, bottom and right edges of each, the last two exclusive
     * @throws MediaException.Malformed if the list is corrupt
     */
    private static List<int[]> trims(byte[] list) {
        var result = new ArrayList<int[]>();
        var reader = new Reader(list);
        var count = reader.u32();
        if (count > MAX_OPCODES) {
            throw new MediaException.Malformed("DNG opcode list of " + count + " opcodes");
        }
        for (var index = 0; index < count; index++) {
            reader.end = list.length;
            var id = reader.int32();
            reader.u32();
            reader.u32();
            var length = reader.u32();
            if (length > list.length - reader.position) {
                throw new MediaException.Malformed("DNG opcode parameters overrun the list");
            }
            var start = reader.position;
            reader.end = start + (int) length;
            if (id == 6) {
                result.add(readTrim(reader));
            }
            reader.position = start + (int) length;
        }
        return result;
    }

    /**
     * Reads the rectangle of a {@code TrimBounds} opcode.
     *
     * @param reader the parameters
     * @return the top, left, bottom and right edges, the last two exclusive
     */
    private static int[] readTrim(Reader reader) {
        return new int[]{reader.int32(), reader.int32(), reader.int32(), reader.int32()};
    }

    /**
     * Returns a view of a surface restricted to a rectangle, in that rectangle's coordinates.
     *
     * @param surface the surface to restrict
     * @param trim the top, left, bottom and right edges, the last two exclusive
     * @return the view, or the surface itself when the rectangle is empty or covers it all
     */
    private static Surface trimmed(Surface surface, int[] trim) {
        var top = Math.clamp(trim[0], 0, surface.height());
        var left = Math.clamp(trim[1], 0, surface.width());
        var bottom = Math.clamp(trim[2], top, surface.height());
        var right = Math.clamp(trim[3], left, surface.width());
        if (bottom == top || right == left || (top == 0 && left == 0 && bottom == surface.height() && right == surface.width())) {
            return surface;
        }
        return new Surface() {
            @Override
            public int width() {
                return right - left;
            }

            @Override
            public int height() {
                return bottom - top;
            }

            @Override
            public int planes() {
                return surface.planes();
            }

            @Override
            public float get(int row, int column, int plane) {
                return surface.get(row + top, column + left, plane);
            }

            @Override
            public void set(int row, int column, int plane, float value) {
                surface.set(row + top, column + left, plane, value);
            }

            @Override
            public float maximum() {
                return surface.maximum();
            }
        };
    }

    /**
     * Applies {@code MapTable}.
     *
     * @param reader the parameters
     * @param surface the image
     */
    private static void mapTable(Reader reader, Surface surface) {
        var area = Area.read(reader, surface);
        var size = reader.int32();
        if (size < 1 || size > 65536) {
            throw new MediaException.Malformed("DNG MapTable of " + size + " entries");
        }
        var table = new float[size];
        for (var index = 0; index < size; index++) {
            table[index] = reader.u16();
        }
        var scale = surface.maximum() == 1 ? 1f / 65535f : 1f;
        var inputScale = surface.maximum() == 1 ? 65535f : 1f;
        for (var row = area.top; row < area.bottom; row += area.rowPitch) {
            for (var column = area.left; column < area.right; column += area.colPitch) {
                for (var plane = area.plane; plane < area.plane + area.planes; plane++) {
                    var value = Math.round(surface.get(row, column, plane) * inputScale);
                    surface.set(row, column, plane, table[Math.clamp(value, 0, size - 1)] * scale);
                }
            }
        }
    }

    /**
     * Applies {@code MapPolynomial}.
     *
     * @param reader the parameters
     * @param surface the image
     */
    private static void mapPolynomial(Reader reader, Surface surface) {
        var area = Area.read(reader, surface);
        var degree = reader.int32();
        if (degree > 8) {
            throw new MediaException.Malformed("DNG MapPolynomial of degree " + degree);
        }
        var coefficients = new double[degree + 1];
        for (var index = 0; index <= degree; index++) {
            coefficients[index] = reader.f64();
        }
        var normalize = surface.maximum() == 1 ? 1.0 : 1 / 65535.0;
        for (var row = area.top; row < area.bottom; row += area.rowPitch) {
            for (var column = area.left; column < area.right; column += area.colPitch) {
                for (var plane = area.plane; plane < area.plane + area.planes; plane++) {
                    var x = surface.get(row, column, plane) * normalize;
                    var y = 0.0;
                    for (var k = degree; k >= 0; k--) {
                        y = y * x + coefficients[k];
                    }
                    surface.set(row, column, plane, (float) (y / normalize));
                }
            }
        }
    }

    /**
     * Applies {@code DeltaPerRow}, {@code DeltaPerColumn}, {@code ScalePerRow} or
     * {@code ScalePerColumn}.
     *
     * @param reader the parameters
     * @param surface the image
     * @param id the opcode
     */
    private static void perLine(Reader reader, Surface surface, int id) {
        var area = Area.read(reader, surface);
        var count = reader.int32();
        if (count > 1 << 20) {
            throw new MediaException.Malformed("DNG opcode of " + count + " values");
        }
        var values = new float[count];
        for (var index = 0; index < count; index++) {
            values[index] = reader.f32();
        }
        var byRow = id == 10 || id == 12;
        var scale = id >= 12;
        for (var row = area.top; row < area.bottom; row += area.rowPitch) {
            var rowIndex = (row - area.top) / area.rowPitch;
            for (var column = area.left; column < area.right; column += area.colPitch) {
                var columnIndex = (column - area.left) / area.colPitch;
                var index = byRow ? rowIndex : columnIndex;
                if (index >= count) {
                    continue;
                }
                for (var plane = area.plane; plane < area.plane + area.planes; plane++) {
                    var value = surface.get(row, column, plane);
                    surface.set(row, column, plane, scale ? value * values[index] : value + values[index]);
                }
            }
        }
    }

    /**
     * Applies {@code GainMap}: bilinear interpolation of a coarse gain grid, replicated outside it.
     *
     * @param reader the parameters
     * @param surface the image
     */
    private static void gainMap(Reader reader, Surface surface) {
        var area = Area.read(reader, surface);
        var pointsV = reader.int32();
        var pointsH = reader.int32();
        var spacingV = reader.f64();
        var spacingH = reader.f64();
        var originV = reader.f64();
        var originH = reader.f64();
        var mapPlanes = reader.int32();
        if (pointsV < 1 || pointsH < 1 || mapPlanes < 1 || (long) pointsV * pointsH * mapPlanes > 1 << 22
                || !(spacingV > 0) || !(spacingH > 0)) {
            throw new MediaException.Malformed("invalid DNG GainMap");
        }
        var gains = new float[pointsV * pointsH * mapPlanes];
        for (var index = 0; index < gains.length; index++) {
            gains[index] = reader.f32();
        }
        var height = surface.height();
        var width = surface.width();
        var columnPosition = new float[Math.max(0, area.right - area.left)];
        for (var column = area.left; column < area.right; column += area.colPitch) {
            columnPosition[column - area.left] = (float) Math.clamp(((double) column / width - originH) / spacingH,
                    0, pointsH - 1);
        }
        for (var row = area.top; row < area.bottom; row += area.rowPitch) {
            var v = Math.clamp(((double) row / height - originV) / spacingV, 0, pointsV - 1);
            var v0 = Math.min((int) v, pointsV - 1);
            var v1 = Math.min(v0 + 1, pointsV - 1);
            var fv = (float) (v - v0);
            for (var column = area.left; column < area.right; column += area.colPitch) {
                var h = columnPosition[column - area.left];
                var h0 = Math.min((int) h, pointsH - 1);
                var h1 = Math.min(h0 + 1, pointsH - 1);
                var fh = h - h0;
                for (var plane = area.plane; plane < area.plane + area.planes; plane++) {
                    var mapPlane = Math.min(plane - area.plane, mapPlanes - 1);
                    var g00 = gains[(v0 * pointsH + h0) * mapPlanes + mapPlane];
                    var g01 = gains[(v0 * pointsH + h1) * mapPlanes + mapPlane];
                    var g10 = gains[(v1 * pointsH + h0) * mapPlanes + mapPlane];
                    var g11 = gains[(v1 * pointsH + h1) * mapPlanes + mapPlane];
                    var top = g00 + (g01 - g00) * fh;
                    var bottom = g10 + (g11 - g10) * fh;
                    var gain = top + (bottom - top) * fv;
                    surface.set(row, column, plane, surface.get(row, column, plane) * gain);
                }
            }
        }
    }

    /**
     * Applies {@code FixVignetteRadial}.
     *
     * @param reader the parameters
     * @param surface the image
     */
    private static void fixVignetteRadial(Reader reader, Surface surface) {
        var k = new double[5];
        for (var index = 0; index < 5; index++) {
            k[index] = reader.f64();
        }
        var cx = reader.f64();
        var cy = reader.f64();
        var width = surface.width();
        var height = surface.height();
        var xc = cx * (width - 1);
        var yc = cy * (height - 1);
        var mx = Math.max(Math.abs(xc), Math.abs(width - 1 - xc));
        var my = Math.max(Math.abs(yc), Math.abs(height - 1 - yc));
        var m = Math.sqrt(mx * mx + my * my);
        if (!(m > 0)) {
            return;
        }
        for (var row = 0; row < height; row++) {
            var dy = (row - yc) / m;
            for (var column = 0; column < width; column++) {
                var dx = (column - xc) / m;
                var r2 = dx * dx + dy * dy;
                var gain = (float) (1 + r2 * (k[0] + r2 * (k[1] + r2 * (k[2] + r2 * (k[3] + r2 * k[4])))));
                for (var plane = 0; plane < surface.planes(); plane++) {
                    surface.set(row, column, plane, surface.get(row, column, plane) * gain);
                }
            }
        }
    }

    /**
     * Applies {@code WarpRectilinear}: radial and tangential distortion per plane, resampled with
     * a Catmull-Rom cubic.
     *
     * @param reader the parameters
     * @param surface the image
     */
    private static void warpRectilinear(Reader reader, Surface surface) {
        var sets = reader.int32();
        if (sets < 1 || sets > 16) {
            throw new MediaException.Malformed("DNG WarpRectilinear with " + sets + " coefficient sets");
        }
        var coefficients = new double[sets][6];
        for (var set = 0; set < sets; set++) {
            for (var index = 0; index < 6; index++) {
                coefficients[set][index] = reader.f64();
            }
        }
        var cx = reader.f64();
        var cy = reader.f64();
        var width = surface.width();
        var height = surface.height();
        var xc = cx * (width - 1);
        var yc = cy * (height - 1);
        var mx = Math.max(Math.abs(xc), Math.abs(width - 1 - xc));
        var my = Math.max(Math.abs(yc), Math.abs(height - 1 - yc));
        var m = Math.sqrt(mx * mx + my * my);
        if (!(m > 0)) {
            return;
        }
        for (var plane = 0; plane < surface.planes(); plane++) {
            var c = coefficients[Math.min(plane, sets - 1)];
            var source = new float[width * height];
            for (var row = 0; row < height; row++) {
                for (var column = 0; column < width; column++) {
                    source[row * width + column] = surface.get(row, column, plane);
                }
            }
            for (var row = 0; row < height; row++) {
                var dy = (row - yc) / m;
                for (var column = 0; column < width; column++) {
                    var dx = (column - xc) / m;
                    var r2 = dx * dx + dy * dy;
                    var f = c[0] + r2 * (c[1] + r2 * (c[2] + r2 * c[3]));
                    var tx = c[4] * 2 * dx * dy + c[5] * (r2 + 2 * dx * dx);
                    var ty = c[5] * 2 * dx * dy + c[4] * (r2 + 2 * dy * dy);
                    var sx = xc + m * (f * dx + tx);
                    var sy = yc + m * (f * dy + ty);
                    surface.set(row, column, plane, cubic(source, width, height, sx, sy));
                }
            }
        }
    }

    /**
     * Applies {@code WarpFisheye}: each output pixel at normalized offset {@code (dx, dy)} from the
     * optical center, at distance {@code r}, takes the source pixel at
     * {@code center + m * f * (dx, dy)}, where {@code m} is the distance from the center to the
     * farthest corner, {@code t = atan(r)} and
     * {@code f = (k0 * t + k1 * t^3 + k2 * t^5 + k3 * t^7) / r} ({@code k0} at the center).
     *
     * @param reader the parameters
     * @param surface the image
     */
    private static void warpFisheye(Reader reader, Surface surface) {
        var sets = reader.int32();
        if (sets < 1 || sets > 16) {
            throw new MediaException.Malformed("DNG WarpFisheye with " + sets + " coefficient sets");
        }
        var coefficients = new double[sets][4];
        for (var set = 0; set < sets; set++) {
            for (var index = 0; index < 4; index++) {
                coefficients[set][index] = reader.f64();
            }
        }
        var cx = reader.f64();
        var cy = reader.f64();
        warp(surface, cx, cy, (plane, dx, dy, out) -> {
            var k = coefficients[Math.min(plane, sets - 1)];
            var r = Math.sqrt(dx * dx + dy * dy);
            double f;
            if (r < 1e-12) {
                f = k[0];
            } else {
                var t = StrictMath.atan(r);
                var t2 = t * t;
                f = t * (k[0] + t2 * (k[1] + t2 * (k[2] + t2 * k[3]))) / r;
            }
            out[0] = f * dx;
            out[1] = f * dy;
        });
    }

    /**
     * Applies {@code WarpRectilinear2}: the radial factor is the polynomial
     * {@code f(r) = k0 + k1 * r + ... + k14 * r^14} of the distance clamped to the valid radius
     * range, multiplying the offset (dividing it when the reciprocal flag is set), plus the
     * tangential terms of {@code WarpRectilinear}.
     *
     * @param reader the parameters
     * @param surface the image
     */
    private static void warpRectilinear2(Reader reader, Surface surface) {
        var sets = reader.int32();
        if (sets < 1 || sets > 16) {
            throw new MediaException.Malformed("DNG WarpRectilinear2 with " + sets + " coefficient sets");
        }
        var coefficients = new double[sets][19];
        for (var set = 0; set < sets; set++) {
            for (var index = 0; index < 19; index++) {
                coefficients[set][index] = reader.f64();
            }
        }
        var cx = reader.f64();
        var cy = reader.f64();
        var reciprocal = reader.u32() != 0;
        warp(surface, cx, cy, (plane, dx, dy, out) -> {
            var c = coefficients[Math.min(plane, sets - 1)];
            var r2 = dx * dx + dy * dy;
            var r = Math.clamp(Math.sqrt(r2), Math.min(c[17], c[18]), Math.max(c[17], c[18]));
            var f = 0.0;
            for (var power = 14; power >= 0; power--) {
                f = f * r + c[power];
            }
            if (reciprocal) {
                f = f != 0 ? 1 / f : 0;
            }
            out[0] = f * dx + c[15] * 2 * dx * dy + c[16] * (r2 + 2 * dx * dx);
            out[1] = f * dy + c[16] * 2 * dx * dy + c[15] * (r2 + 2 * dy * dy);
        });
    }

    /**
     * Maps the normalized offset of an output pixel from the optical center to the normalized
     * offset of its source pixel.
     */
    @FunctionalInterface
    private interface WarpFunction {
        /**
         * Computes the source offset.
         *
         * @param plane the plane index
         * @param dx the horizontal offset of the output pixel
         * @param dy the vertical offset of the output pixel
         * @param out receives the horizontal and vertical source offsets
         */
        void map(int plane, double dx, double dy, double[] out);
    }

    /**
     * Resamples every plane of an image through a warp function, offsets measured from the
     * optical center in units of the distance to the farthest corner.
     *
     * @param surface the image
     * @param cx the normalized horizontal position of the optical center
     * @param cy the normalized vertical position of the optical center
     * @param warp the warp function
     */
    private static void warp(Surface surface, double cx, double cy, WarpFunction warp) {
        var width = surface.width();
        var height = surface.height();
        var xc = cx * (width - 1);
        var yc = cy * (height - 1);
        var mx = Math.max(Math.abs(xc), Math.abs(width - 1 - xc));
        var my = Math.max(Math.abs(yc), Math.abs(height - 1 - yc));
        var m = Math.sqrt(mx * mx + my * my);
        if (!(m > 0)) {
            return;
        }
        var offset = new double[2];
        for (var plane = 0; plane < surface.planes(); plane++) {
            var source = new float[width * height];
            for (var row = 0; row < height; row++) {
                for (var column = 0; column < width; column++) {
                    source[row * width + column] = surface.get(row, column, plane);
                }
            }
            for (var row = 0; row < height; row++) {
                var dy = (row - yc) / m;
                for (var column = 0; column < width; column++) {
                    warp.map(plane, (column - xc) / m, dy, offset);
                    surface.set(row, column, plane, cubic(source, width, height, xc + m * offset[0], yc + m * offset[1]));
                }
            }
        }
    }

    /**
     * Samples an image at a fractional position with a Catmull-Rom cubic, edges replicated.
     *
     * @param source the image
     * @param width its width
     * @param height its height
     * @param x the column
     * @param y the row
     * @return the sample
     */
    private static float cubic(float[] source, int width, int height, double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            return 0;
        }
        var x0 = (int) Math.floor(x);
        var y0 = (int) Math.floor(y);
        var fx = x - x0;
        var fy = y - y0;
        var wx = weights(fx);
        var wy = weights(fy);
        var sum = 0.0;
        for (var j = 0; j < 4; j++) {
            var row = Math.clamp(y0 - 1 + j, 0, height - 1);
            var line = 0.0;
            for (var i = 0; i < 4; i++) {
                var column = Math.clamp(x0 - 1 + i, 0, width - 1);
                line += wx[i] * source[row * width + column];
            }
            sum += wy[j] * line;
        }
        return (float) sum;
    }

    /**
     * Returns the Catmull-Rom weights of the four taps around a fractional offset.
     *
     * @param t the offset, from 0 to 1
     * @return the weights
     */
    private static double[] weights(double t) {
        var t2 = t * t;
        var t3 = t2 * t;
        return new double[]{
                -0.5 * t3 + t2 - 0.5 * t,
                1.5 * t3 - 2.5 * t2 + 1,
                -1.5 * t3 + 2 * t2 + 0.5 * t,
                0.5 * t3 - 0.5 * t2
        };
    }

    /**
     * Applies {@code FixBadPixelsConstant}: every photosite holding the constant is replaced by the
     * mean of its nearest same-color neighbors that do not.
     *
     * @param reader the parameters
     * @param surface the image
     */
    private static void fixBadPixelsConstant(Reader reader, Surface surface) {
        var constant = reader.u32();
        var phase = reader.int32();
        var target = surface.maximum() == 1 ? constant / 65535f : (float) constant;
        for (var row = 0; row < surface.height(); row++) {
            for (var column = 0; column < surface.width(); column++) {
                if (surface.get(row, column, 0) == target) {
                    patch(surface, row, column, phase, target);
                }
            }
        }
    }

    /**
     * Applies {@code FixBadPixelsList}.
     *
     * @param reader the parameters
     * @param surface the image
     */
    private static void fixBadPixelsList(Reader reader, Surface surface) {
        var phase = reader.int32();
        var points = reader.int32();
        var rects = reader.int32();
        if ((long) points * 8 + (long) rects * 16 > reader.end - reader.position) {
            throw new MediaException.Malformed("truncated DNG FixBadPixelsList");
        }
        for (var index = 0; index < points; index++) {
            var row = reader.int32();
            var column = reader.int32();
            if (row < surface.height() && column < surface.width()) {
                patch(surface, row, column, phase, Float.NaN);
            }
        }
        for (var index = 0; index < rects; index++) {
            var top = reader.int32();
            var left = reader.int32();
            var bottom = Math.min(reader.int32(), surface.height());
            var right = Math.min(reader.int32(), surface.width());
            for (var row = top; row < bottom; row++) {
                for (var column = left; column < right; column++) {
                    patch(surface, row, column, phase, Float.NaN);
                }
            }
        }
    }

    /**
     * Replaces a bad photosite of a Bayer image by the mean of its same-color neighbors within
     * two photosites.
     *
     * @param surface the image
     * @param row the row of the bad photosite
     * @param column the column of the bad photosite
     * @param phase the Bayer phase of the top-left photosite: 0 red, 1 green in a red row, 2
     * green in a blue row, 3 blue
     * @param bad the value marking other bad photosites, or {@code NaN}
     */
    private static void patch(Surface surface, int row, int column, int phase, float bad) {
        var greenAtOrigin = phase == 1 || phase == 2;
        var green = (((row + column) & 1) == 0) == greenAtOrigin;
        var sum = 0f;
        var count = 0;
        for (var dy = -2; dy <= 2; dy++) {
            for (var dx = -2; dx <= 2; dx++) {
                if (dy == 0 && dx == 0) {
                    continue;
                }
                var sameColor = green ? ((dy + dx) & 1) == 0 : (dy & 1) == 0 && (dx & 1) == 0;
                var y = row + dy;
                var x = column + dx;
                if (!sameColor || y < 0 || x < 0 || y >= surface.height() || x >= surface.width()) {
                    continue;
                }
                var value = surface.get(y, x, 0);
                if (value != bad) {
                    sum += value;
                    count++;
                }
            }
        }
        if (count > 0) {
            surface.set(row, column, 0, sum / count);
        }
    }
}
